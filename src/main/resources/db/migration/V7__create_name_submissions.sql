CREATE TABLE name_submissions (
    id              BIGSERIAL PRIMARY KEY,
    display_name    VARCHAR(128) NOT NULL,
    normalized_name VARCHAR(128) NOT NULL,
    race            VARCHAR(32)  NOT NULL CHECK (race IN
        ('ELF', 'DWARF', 'HUMAN', 'HALFLING', 'ORC', 'GNOME', 'DRAGONBORN', 'TIEFLING')),
    gender          VARCHAR(16)  NOT NULL CHECK (gender IN ('MASCULINE', 'FEMININE')),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitter_id    BIGINT       NOT NULL REFERENCES users (id),
    reviewer_id     BIGINT       REFERENCES users (id),
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- One live pending submission per (name, race, gender). Rejected/approved rows
    -- don't block a future resubmission because they're excluded from this uniqueness key.
    CONSTRAINT uq_submissions_pending UNIQUE (normalized_name, race, gender, status)
);

-- FK-referencing columns are never auto-indexed by Postgres (same reasoning as
-- idx_favorites_owner_id in V4): back the admin-queue read and the submitter lookups.
CREATE INDEX idx_submissions_status ON name_submissions (status, created_at);
CREATE INDEX idx_submissions_submitter ON name_submissions (submitter_id);