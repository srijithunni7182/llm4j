package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunJournal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase 34c, D8, D9 and D13: the mailbox is entries in the receiver's journal. These tests are the
 * contract the rest of signals stands on: order, the two kinds (message and sticky), the limits
 * that fail loudly, the epoch that keeps an old generation's signals out of a new one, and a send
 * that happens once however often the sender's step is replayed. They need no model and no thread
 * except the one that races sends on purpose.
 */
class MailboxTest {

    private final RunJournal journal = RunJournal.inMemory();

    private Mailbox.Signal send(String name, String text) {
        return Mailbox.send(journal, Mailbox.Direction.IN, name, text, false, "Main/s" + System.nanoTime());
    }

    // ---- order, kinds ----

    @Test
    void messagesOfOneNameComeOutInTheOrderTheyWereSent() {
        send("go", "one");
        send("go", "two");
        send("go", "three");

        for (String expected : List.of("one", "two", "three")) {
            Mailbox.Signal next = Mailbox.next(journal, Mailbox.Direction.IN, "go").orElseThrow();
            assertThat(next.text()).isEqualTo(expected);
            Mailbox.take(journal, Mailbox.Direction.IN, next);
        }
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "go")).as("nothing more").isEmpty();
    }

    @Test
    void namesDoNotMixAndAnUnsentNameIsEmpty() {
        send("go", "a");
        send("stop", "b");

        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "stop").orElseThrow().text()).isEqualTo("b");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "nobody")).isEmpty();
    }

    @Test
    void theTwoDirectionsAreSeparateMailboxes() {
        Mailbox.send(journal, Mailbox.Direction.OUT, "stuck", "from the child", false, "Child/s1");

        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "stuck")).isEmpty();
        assertThat(Mailbox.next(journal, Mailbox.Direction.OUT, "stuck").orElseThrow().text()).isEqualTo("from the child");
    }

    @Test
    void aStickySignalIsReadWithoutBeingConsumedAndANewOneReplacesIt() {
        Mailbox.send(journal, Mailbox.Direction.IN, "approved", "yes", true, "Main/s1");

        for (int i = 0; i < 5; i++) {
            Mailbox.Signal read = Mailbox.next(journal, Mailbox.Direction.IN, "approved").orElseThrow();
            assertThat(read.text()).as("read again and again").isEqualTo("yes");
            Mailbox.take(journal, Mailbox.Direction.IN, read); // a read of a latch takes nothing
        }

        Mailbox.send(journal, Mailbox.Direction.IN, "approved", "revoked", true, "Main/s2");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "approved").orElseThrow().text()).isEqualTo("revoked");
    }

    @Test
    void aStickySignalDoesNotTakeAMessageOfTheSameNameOutOfOrder() {
        Mailbox.send(journal, Mailbox.Direction.IN, "state", "latched", true, "Main/s1");
        Mailbox.send(journal, Mailbox.Direction.IN, "state", "m1", false, "Main/s2");

        // a message is preferred over the latch: it is consumed once, then the latch shows again
        Mailbox.Signal first = Mailbox.next(journal, Mailbox.Direction.IN, "state").orElseThrow();
        assertThat(first.text()).isEqualTo("m1");
        Mailbox.take(journal, Mailbox.Direction.IN, first);
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "state").orElseThrow().text()).isEqualTo("latched");
    }

    @Test
    void aSendCarriesItsSequenceNumberAndItsSender() {
        Mailbox.Signal first = Mailbox.send(journal, Mailbox.Direction.IN, "go", "x", false, "Main/s1");
        Mailbox.Signal second = Mailbox.send(journal, Mailbox.Direction.IN, "go", "y", false, "Main/s2");

        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(first.from()).isEqualTo("Main/s1");
        assertThat(Mailbox.all(journal, Mailbox.Direction.IN)).extracting(Mailbox.Signal::text).containsExactly("x", "y");
    }

    // ---- idempotence: a replayed sender step sends once ----

    @Test
    void sendingFromTheSameStepAgainReturnsTheSignalAlreadySentAndWritesNothingNew() {
        Mailbox.Signal first = Mailbox.send(journal, Mailbox.Direction.IN, "go", "x", false, "Main/s1");
        int entries = journal.all().size();

        Mailbox.Signal again = Mailbox.send(journal, Mailbox.Direction.IN, "go", "x", false, "Main/s1");

        assertThat(again).isEqualTo(first);
        assertThat(journal.all()).hasSize(entries);
    }

    // ---- limits fail loudly (G24) ----

    @Test
    void theSeventeenthPendingMessageOfOneNameFailsNamingTheLimit() {
        for (int i = 0; i < Mailbox.MAX_PER_NAME; i++) send("go", "m" + i);

        assertThatThrownBy(() -> send("go", "one too many"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("go")
                .hasMessageContaining("16");
        assertThat(Mailbox.all(journal, Mailbox.Direction.IN)).as("nothing was dropped silently or half written").hasSize(16);
    }

    @Test
    void theSixtyFifthPendingMessageInAllFailsNamingTheLimitEvenAcrossNames() {
        for (int i = 0; i < Mailbox.MAX_TOTAL; i++) send("n" + (i / 8), "m" + i); // 8 names of 8

        assertThatThrownBy(() -> send("another", "x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("64");
    }

    @Test
    void aStickySignalCountsAsOnePerNameHoweverOftenItIsReplaced() {
        for (int i = 0; i < 200; i++) Mailbox.send(journal, Mailbox.Direction.IN, "state", "v" + i, true, "Main/s" + i);

        assertThat(Mailbox.pendingTotal(journal, Mailbox.Direction.IN)).isEqualTo(1);
    }

    @Test
    void aNameIsAPlainWordOfAtMostFortyCharacters() {
        assertThatThrownBy(() -> send("a".repeat(41), "x")).hasMessageContaining("40");
        assertThatThrownBy(() -> send("has space", "x")).hasMessageContaining("plain word");
        assertThatThrownBy(() -> send("", "x")).hasMessageContaining("plain word");
        assertThatThrownBy(() -> send("../etc", "x")).hasMessageContaining("plain word");
        send("a".repeat(40), "x"); // exactly forty is fine
        send("under_score_9", "x");
    }

    @Test
    void aPayloadIsAtMostTwoThousandCharactersAndExactlyThatIsFine() {
        send("go", "x".repeat(2000));
        assertThatThrownBy(() -> send("go", "x".repeat(2001))).hasMessageContaining("2000");
    }

    @Test
    void aMissingPayloadIsEmptyText() {
        assertThat(send("go", null).text()).isEmpty();
    }

    @Test
    void takenMessagesNoLongerCountAsPendingSoTheLimitIsOfWhatIsWaiting() {
        for (int i = 0; i < Mailbox.MAX_PER_NAME; i++) send("go", "m" + i);
        for (int i = 0; i < 10; i++) Mailbox.take(journal, Mailbox.Direction.IN, Mailbox.next(journal, Mailbox.Direction.IN, "go").orElseThrow());

        assertThat(Mailbox.pending(journal, Mailbox.Direction.IN, "go")).isEqualTo(6);
        for (int i = 0; i < 10; i++) send("go", "later" + i); // ten more fit
        assertThatThrownBy(() -> send("go", "no more")).hasMessageContaining("16");
    }

    @Test
    void takingTheSameMessageTwiceIsTheSameAsTakingItOnce() {
        Mailbox.Signal only = send("go", "x");
        Mailbox.take(journal, Mailbox.Direction.IN, only);
        int entries = journal.all().size();
        Mailbox.take(journal, Mailbox.Direction.IN, only);

        assertThat(journal.all()).hasSize(entries);
        assertThat(Mailbox.pending(journal, Mailbox.Direction.IN, "go")).isZero();
    }

    // ---- the epoch: a rewound child starts with an empty mailbox (D13.5) ----

    @Test
    void aSignalSentBeforeARewindIsNotDeliveredAfterIt() {
        send("go", "before");
        // the child is sent back: its journal now records one boundary, a new epoch
        new Generations(journal).start("Child/s", 1, "cp", "parent", null, "again", Map.of(), "keep", false);

        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "go")).as("the old generation's signal is history").isEmpty();
        assertThat(Mailbox.all(journal, Mailbox.Direction.IN)).as("it stays in the journal as history").hasSize(1);

        send("go", "after");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "go").orElseThrow().text()).isEqualTo("after");
    }

    @Test
    void aStickySignalSentBeforeARewindMustBeSentAgain() {
        Mailbox.send(journal, Mailbox.Direction.IN, "approved", "yes", true, "Main/s1");
        new Generations(journal).start("Child/s", 1, "cp", "parent", null, "again", Map.of(), "keep", false);

        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "approved")).isEmpty();
    }

    // ---- non-functional: bounded work, bounded growth, no lost send under a race ----

    @Test
    void concurrentSendersNeverLoseOrDuplicateAMessageAndNeverPassTheLimit() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < 8; t++) {
            int id = t;
            pool.submit(
                    () -> {
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        for (int i = 0; i < 20; i++) {
                            try {
                                Mailbox.send(journal, Mailbox.Direction.IN, "race", "t" + id + "m" + i, false, "Main/t" + id + "/" + i);
                                accepted.incrementAndGet();
                            } catch (IllegalStateException full) {
                                refused.incrementAndGet();
                            }
                        }
                    });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        List<Mailbox.Signal> all = Mailbox.all(journal, Mailbox.Direction.IN);
        assertThat(all).hasSize(Mailbox.MAX_PER_NAME).hasSize(accepted.get());
        assertThat(all).extracting(Mailbox.Signal::seq).doesNotHaveDuplicates();
        assertThat(refused.get()).isEqualTo(160 - Mailbox.MAX_PER_NAME);
    }

    @Test
    void readingAMailboxOfSixtyFourScansTheJournalOnceNotOncePerMessage() {
        for (int i = 0; i < Mailbox.MAX_TOTAL; i++) send("n" + (i % 8), "m" + i);
        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) Mailbox.next(journal, Mailbox.Direction.IN, "n3");
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).as("2000 reads of a full mailbox").isLessThan(2000);
    }

    // ---- persistence: the mailbox is the same after the journal is read back from a file ----

    @Test
    void aMailboxSurvivesAFileJournalBeingReadBackWithItsOrderItsTakenMarkersAndItsLimits() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("mailbox").resolve("journal.json");
        try {
            RunJournal first = new io.github.llm4j.loom.runtime.FileRunJournal(file);
            Mailbox.send(first, Mailbox.Direction.IN, "go", "one", false, "Main/s1");
            Mailbox.send(first, Mailbox.Direction.IN, "go", "two", false, "Main/s2");
            Mailbox.send(first, Mailbox.Direction.IN, "approved", "yes", true, "Main/s3");
            Mailbox.take(first, Mailbox.Direction.IN, Mailbox.next(first, Mailbox.Direction.IN, "go").orElseThrow());

            RunJournal second = new io.github.llm4j.loom.runtime.FileRunJournal(file); // a restart
            assertThat(Mailbox.next(second, Mailbox.Direction.IN, "go").orElseThrow().text()).isEqualTo("two");
            assertThat(Mailbox.next(second, Mailbox.Direction.IN, "approved").orElseThrow().text()).isEqualTo("yes");
            assertThat(Mailbox.pending(second, Mailbox.Direction.IN, "go")).isEqualTo(1);
            assertThat(Mailbox.send(second, Mailbox.Direction.IN, "go", "one", false, "Main/s1").text()).as("a replayed send is the one already there").isEqualTo("one");
            assertThat(Mailbox.all(second, Mailbox.Direction.IN)).hasSize(3);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(file.getParent());
        }
    }

    // ---- the total limit, at its edge, for a latch and for a message ----

    /** Fills the mailbox to {@code total} unread signals, one latch ("keep") and the rest messages over four names. */
    private void fill(int total) {
        Mailbox.send(journal, Mailbox.Direction.IN, "keep", "latch", true, "latchstep");
        String[] names = {"m0", "m1", "m2", "m3"};
        int sent = 1;
        for (int n = 0; sent < total; n++, sent++) send(names[n % 4], "x");
    }

    @Test
    void aNewLatchIsRefusedOnlyOnceSixtyFourSignalsAreWaiting() {
        fill(Mailbox.MAX_TOTAL - 1);
        assertThat(Mailbox.pendingTotal(journal, Mailbox.Direction.IN)).isEqualTo(Mailbox.MAX_TOTAL - 1);
        assertThat(Mailbox.send(journal, Mailbox.Direction.IN, "fresh", "ok", true, "f1").sticky()).isTrue(); // the 64th

        assertThatThrownBy(() -> Mailbox.send(journal, Mailbox.Direction.IN, "another", "no", true, "f2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64");
    }

    @Test
    void aLatchThatIsAlreadySetMayBeReplacedEvenWhenTheMailboxIsFull() {
        fill(Mailbox.MAX_TOTAL);
        assertThat(Mailbox.pendingTotal(journal, Mailbox.Direction.IN)).isEqualTo(Mailbox.MAX_TOTAL);

        Mailbox.Signal replaced = Mailbox.send(journal, Mailbox.Direction.IN, "keep", "newer", true, "latchstep2");

        assertThat(replaced.text()).isEqualTo("newer");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "keep").orElseThrow().text()).isEqualTo("newer");
        assertThat(Mailbox.pendingTotal(journal, Mailbox.Direction.IN)).as("a latch counts once however often it is set").isEqualTo(Mailbox.MAX_TOTAL);
    }

    @Test
    void aMessageIsRefusedWhenSixtyFourAreWaitingAndAllowedAtSixtyThree() {
        fill(Mailbox.MAX_TOTAL - 1);
        Mailbox.send(journal, Mailbox.Direction.IN, "elsewhere", "64th", false, "e1");

        assertThatThrownBy(() -> Mailbox.send(journal, Mailbox.Direction.IN, "another", "65th", false, "e2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 signals that have not been read");
    }

    @Test
    void aLatchOfOneNameIsNotTheLatchOfAnother() {
        Mailbox.send(journal, Mailbox.Direction.IN, "a", "A", true, "s1");
        Mailbox.send(journal, Mailbox.Direction.IN, "b", "B", true, "s2");

        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "a").orElseThrow().text()).isEqualTo("A");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "b").orElseThrow().text()).isEqualTo("B");
        assertThat(Mailbox.next(journal, Mailbox.Direction.IN, "c")).isEmpty();
    }
}
