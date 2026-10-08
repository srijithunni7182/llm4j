-- Durable runs: Loom records every step's result here, so a run waiting for a creator holds no thread
-- and a run whose server went away is resumed (not failed) by whichever instance picks it up.
CREATE TABLE loom_journal (
    run_id     VARCHAR(64)  NOT NULL,
    step_id    VARCHAR(512) NOT NULL,
    kind       VARCHAR(20)  NOT NULL,
    value_json TEXT,
    PRIMARY KEY (run_id, step_id)
);

-- A question belongs to a Loom step; the answer is recorded in the journal under that step.
ALTER TABLE run_questions ADD COLUMN step_id VARCHAR(512);

-- How many times a run has been resumed after its server stopped responding.
ALTER TABLE runs ADD COLUMN resumes INT DEFAULT 0 NOT NULL;
