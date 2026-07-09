CREATE TABLE name_submissions (
    id              BIGSERIAL PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    normalized_name VARCHAR(128) NOT NULL,
    race            VARCHAR(32)  NOT NULL CHECK (race IN
        ('ELF', 'DWARF', 'HUMAN', 'HALFLING', 'ORC', 'HALF_ORC', 'GNOME', 'DRAGONBORN', 'TIEFLING')),
    gender          VARCHAR(16)  NOT NULL CHECK (gender IN ('MASCULINE', 'FEMININE')),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitter_id    BIGINT       NOT NULL REFERENCES users (id),
    reviewer_id     BIGINT       REFERENCES users (id),
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One live PENDING submission per (name, race, gender). A partial unique index, not a
-- composite UNIQUE that includes status: folding status into the key would also cap
-- APPROVED and REJECTED rows at one apiece, so rejecting a resubmitted-then-re-rejected
-- name would collide with the earlier REJECTED row and fail the moderation action. Scoping
-- the uniqueness to WHERE status = 'PENDING' enforces "at most one open submission" while
-- leaving any number of resolved (APPROVED/REJECTED) rows for the same name untouched.
CREATE UNIQUE INDEX uq_submissions_pending
    ON name_submissions (normalized_name, race, gender)
    WHERE status = 'PENDING';

-- FK-referencing columns are never auto-indexed by Postgres (same reasoning as
-- idx_favorites_owner_id in V4): back the admin-queue read, the submitter lookups, and the
-- reviewer FK check (a user delete would otherwise seq-scan this table for reviewer_id).
CREATE INDEX idx_submissions_status ON name_submissions (status, created_at);
CREATE INDEX idx_submissions_submitter ON name_submissions (submitter_id);
CREATE INDEX idx_submissions_reviewer ON name_submissions (reviewer_id);
