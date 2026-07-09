-- Roles: varchar + CHECK, matching the race/gender/source/status convention (see
-- docs/DECISIONS.md) rather than a separate roles table -- two fixed values, no per-role
-- metadata, so a lookup table would only add a join for no benefit.
ALTER TABLE users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));

-- Reports go owner-keyed the same way favorites did in V5 (see docs/DECISIONS.md) -- every
-- Identity carries a non-null ownerId now that the app is fully locked down, so there is no
-- longer a gap that only session_id can fill. session_id was NOT NULL; relax it since new rows
-- are written with owner_id instead. The column stays in place, not dropped -- pre-slice-6 rows
-- are session-keyed only and there's no backfill story for them (same reasoning V5 applied to
-- favorites' orphaned session-keyed rows).
ALTER TABLE name_reports ADD COLUMN owner_id BIGINT REFERENCES users (id);
ALTER TABLE name_reports ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE name_reports
    ADD CONSTRAINT uq_name_reports_owner_name UNIQUE (owner_id, name_id);
ALTER TABLE name_reports
    ADD CONSTRAINT chk_name_reports_reporter_present
        CHECK (session_id IS NOT NULL OR owner_id IS NOT NULL);

-- Postgres does not auto-index the referencing side of a FK -- same reasoning as
-- idx_favorites_owner_id in V4.
CREATE INDEX idx_name_reports_owner_id ON name_reports (owner_id);
