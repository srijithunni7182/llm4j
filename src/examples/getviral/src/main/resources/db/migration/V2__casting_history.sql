-- Every casting the Showrunner wrote for a creator (lens, direction, visual style, YouTube packaging…),
-- so the next one can be briefed with it and checked for originality.
CREATE TABLE casting_history (
    run_id       VARCHAR(36) PRIMARY KEY,
    user_id      VARCHAR(36) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    casting_json TEXT        NOT NULL
);
CREATE INDEX casting_history_user ON casting_history (user_id, created_at DESC);
