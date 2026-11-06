package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.Media;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a step was given, written beside its answer in the run journal (docs/specs/
 * 31-multimodal-input, R9 and R20): the kind, type, size, SHA-256 and name of each attachment, never
 * the bytes. A resumed run reuses a recorded answer only if the files are still the ones it was
 * given; a changed, missing, added or dropped file stops the reuse, so a step is never answered
 * about different content under the old record.
 */
final class MediaJournal {

    private static final String SUFFIX = "#media";

    private MediaJournal() {}

    /** Records the attachments of a step; a step with none leaves no record. */
    static void record(RunJournal journal, String stepId, List<Media> media) {
        if (media.isEmpty()) return;
        journal.put(stepId + SUFFIX, new RunJournal.Entry("media", DelegateRunner.describe(media)));
    }

    /**
     * For a step answered by a person: the first time, records what was shown; afterwards, checks it
     * is still what the person saw.
     *
     * @throws MediaRefusal when the files are no longer the ones that were shown
     */
    static void recordOrVerify(RunJournal journal, String stepId, List<Media> media) {
        if (journal.get(stepId + SUFFIX).isPresent() || journal.get(stepId).isPresent()) {
            verify(journal, stepId, media);
        } else {
            record(journal, stepId, media);
        }
    }

    /**
     * Checks the files a step has now against what its recorded answer was given.
     *
     * @throws MediaRefusal when they differ; the recorded answer must not be reused
     */
    static void verify(RunJournal journal, String stepId, List<Media> now) {
        Optional<RunJournal.Entry> recorded = journal.get(stepId + SUFFIX);
        if (recorded.isEmpty() && now.isEmpty()) return;
        if (recorded.isEmpty()) {
            throw new MediaRefusal(
                    "this step now attaches "
                            + names(now)
                            + ", which the recorded answer was not given, so it is not reused;"
                            + " run the step again to answer about them");
        }
        List<Map<?, ?>> before = entries(recorded.get().value());
        if (now.isEmpty()) {
            throw new MediaRefusal(
                    "the recorded answer was given with "
                            + names(before)
                            + ", which this step no longer attaches, so it is not reused; run the"
                            + " step again");
        }
        if (before.size() != now.size()) {
            throw new MediaRefusal(
                    "the recorded answer was given "
                            + before.size()
                            + " attachment(s) and this step attaches "
                            + now.size()
                            + ", so it is not reused; run the step again");
        }
        for (int i = 0; i < now.size(); i++) {
            Object then = before.get(i).get("sha256");
            if (!now.get(i).sha256().equals(then)) {
                throw new MediaRefusal(
                        "attachment "
                                + (i + 1)
                                + " ("
                                + now.get(i).name()
                                + ") has changed since this step was answered, so the recorded"
                                + " answer is about different content and is not reused; run the"
                                + " step again");
            }
        }
    }

    private static List<Map<?, ?>> entries(Object value) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> m) out.add(m);
        }
        return out;
    }

    private static String names(List<?> items) {
        List<String> names = new ArrayList<>();
        for (Object item : items) {
            names.add(item instanceof Media m ? m.name() : String.valueOf(((Map<?, ?>) item).get("name")));
        }
        return String.join(", ", names);
    }
}
