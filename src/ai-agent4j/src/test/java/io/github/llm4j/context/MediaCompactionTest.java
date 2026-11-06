package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.TinyMedia;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R19: compaction keeps the task and its media, and when it removes
 * a message that carried media it says so, instead of letting the pictures vanish unremarked.
 */
class MediaCompactionTest {

    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    private static Media image() {
        return Media.of(MediaKind.IMAGE, TinyMedia.png(), "a.png", POLICY);
    }

    private static Media video() {
        return Media.of(MediaKind.VIDEO, TinyMedia.mp4(), "a.mp4", POLICY);
    }

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).pinned(Messages::isPinned).build();
    }

    /** system, a task with one picture, a user message with two pictures, then ten tool steps. */
    private static List<Message> withMediaEarly() {
        Convo c =
                new Convo()
                        .system("sys")
                        .add(Message.user("task", List.of(image())))
                        .add(Message.user("compare these", List.of(image(), image())));
        for (int i = 0; i < 10; i++) c.step("search", 400);
        return c.build();
    }

    // ── the task keeps its media ───────────────────────────────────────────────────────────

    @Test
    void theFirstUserMessageKeepsItsMediaThroughDropOldest() {
        List<Message> in = withMediaEarly();
        CompactionResult r = ContextCompactors.dropOldest().compact(in, req(1));

        assertThat(r.messages().get(1)).isSameAs(in.get(1));
        assertThat(r.messages().get(1).getMedia()).hasSize(1);
    }

    @Test
    void theFirstUserMessageKeepsItsMediaThroughSummarize() {
        List<Message> in = withMediaEarly();
        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.saying("notes")).compact(in, req(1));

        assertThat(r.messages().get(1)).isSameAs(in.get(1));
    }

    // ── what is removed is named ───────────────────────────────────────────────────────────

    @Test
    void theDropMarkerNamesTheMediaItRemoved() {
        CompactionResult r = ContextCompactors.dropOldest().compact(withMediaEarly(), req(1));

        Message marker = r.messages().get(2);
        assertThat(marker.getContent())
                .contains(
                        "earlier messages were removed to save space, including 2 images that can no longer be seen")
                .endsWith("]");
        assertThat(Messages.droppedMedia(marker)).containsEntry(MediaKind.IMAGE, 2).hasSize(1);
        assertThat(marker.getMedia()).isEmpty();
    }

    @Test
    void aTextOnlyDropMarkerIsExactlyWhatItWasBefore() {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < 10; i++) c.step("search", 400);

        CompactionResult r = ContextCompactors.dropOldest().compact(c.build(), req(1));

        assertThat(r.messages().get(2).getContent())
                .matches("\\[\\d+ earlier messages were removed to save space\\.\\]");
        assertThat(Messages.droppedMedia(r.messages().get(2))).isEmpty();
    }

    @Test
    void mediaDroppedInTwoPassesAddsUp() {
        List<Message> first =
                ContextCompactors.dropOldest().compact(withMediaEarly(), req(1)).messages();
        // later the conversation gets another video, and is compacted again
        Convo c = new Convo();
        first.forEach(c::add);
        c.add(Message.user("and this clip", List.of(video())));
        for (int i = 0; i < 6; i++) c.step("search", 400);

        CompactionResult second = ContextCompactors.dropOldest().compact(c.build(), req(1));

        Message marker = second.messages().get(2);
        assertThat(Messages.droppedMedia(marker))
                .containsEntry(MediaKind.IMAGE, 2)
                .containsEntry(MediaKind.VIDEO, 1);
        assertThat(marker.getContent()).contains("including 2 images and 1 video");
    }

    @Test
    void describesCountsInPlainWords() {
        java.util.Map<MediaKind, Integer> counts = new java.util.EnumMap<>(MediaKind.class);
        counts.put(MediaKind.IMAGE, 1);
        assertThat(Messages.describeMedia(counts)).isEqualTo("1 image");
        counts.put(MediaKind.AUDIO, 3);
        assertThat(Messages.describeMedia(counts)).isEqualTo("1 image and 3 sounds");
        counts.put(MediaKind.VIDEO, 1);
        counts.put(MediaKind.DOCUMENT, 2);
        assertThat(Messages.describeMedia(counts))
                .isEqualTo("1 image, 3 sounds, 1 video and 2 documents");
    }

    // ── the summary ────────────────────────────────────────────────────────────────────────

    @Test
    void theSummarisersTranscriptSaysWhichMessagesHadMediaAttached() {
        SummariserModel model = SummariserModel.saying("They compared two pictures.");
        ContextCompactors.summarize(model).compact(withMediaEarly(), req(1));

        assertThat(model.transcript())
                .contains("User: compare these")
                .contains("2 images were attached");
    }

    @Test
    void theSummaryEndsWithAnExactNoteOfTheMediaThatWentWithIt() {
        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.saying("They compared two pictures."))
                        .compact(withMediaEarly(), req(1));

        Message summary = r.messages().get(2);
        assertThat(summary.getContent())
                .contains("They compared two pictures.")
                .endsWith(
                        "[Media that went with these steps and can no longer be seen: 2 images.]");
        assertThat(Messages.droppedMedia(summary)).containsEntry(MediaKind.IMAGE, 2);
    }

    @Test
    void aTextOnlySummaryHasNoNote() {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < 10; i++) c.step("search", 400);

        CompactionResult r =
                ContextCompactors.summarize(SummariserModel.saying("notes"))
                        .compact(c.build(), req(1));

        assertThat(r.messages().get(2).getContent()).doesNotContain("Media that went");
    }

    @Test
    void aSecondSummaryCarriesTheCountsForwardWithoutRepeatingTheOldNote() {
        List<Message> first =
                ContextCompactors.summarize(SummariserModel.saying("first notes"))
                        .compact(withMediaEarly(), req(1))
                        .messages();
        Convo c = new Convo();
        first.forEach(c::add);
        c.add(Message.user("and this clip", List.of(video())));
        for (int i = 0; i < 6; i++) c.step("search", 400);
        SummariserModel model = SummariserModel.saying("merged notes");

        CompactionResult second = ContextCompactors.summarize(model).compact(c.build(), req(1));

        // the model is not shown the old note (it would copy it), the new note is complete
        assertThat(model.transcript()).doesNotContain("Media that went");
        assertThat(second.messages().get(2).getContent())
                .endsWith(
                        "[Media that went with these steps and can no longer be seen: 2 images and 1 video.]")
                .containsOnlyOnce("Media that went");
    }
}
