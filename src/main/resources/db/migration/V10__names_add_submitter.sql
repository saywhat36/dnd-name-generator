-- Issue #81: the "user submitted" filter lists approved user-submitted names alongside the
-- username of whoever proposed each one. Until now the approve-time insert (SubmissionInsertDao)
-- wrote the name into `names` with no link back to the submitter, so the names table couldn't
-- answer "who proposed this name". Add a nullable submitter FK -- nullable because CURATED and
-- AI rows have no submitter -- populated going forward by the approve insert.
ALTER TABLE names ADD COLUMN submitter_id BIGINT REFERENCES users (id);

-- Backfill existing USER_SUBMITTED rows so already-approved names show a username too, not just
-- ones approved after this migration. Matched on the same (normalized_name, race, gender) key the
-- approve insert dedupes on. A name can have more than one APPROVED submission (approved, later
-- flagged/removed, resubmitted, re-approved); UPDATE ... FROM picks one arbitrarily, which is fine
-- -- any approver of this exact name is a correct attribution.
UPDATE names n
SET submitter_id = s.submitter_id
FROM name_submissions s
WHERE n.source = 'USER_SUBMITTED'
  AND s.status = 'APPROVED'
  AND s.normalized_name = n.normalized_name
  AND s.race = n.race
  AND s.gender = n.gender;

-- FK-referencing columns are never auto-indexed by Postgres (same reasoning as V7's
-- idx_submissions_submitter): back the user-submitted view's submitter lookups and keep a user
-- delete from seq-scanning this table for the FK check.
CREATE INDEX idx_names_submitter ON names (submitter_id);
