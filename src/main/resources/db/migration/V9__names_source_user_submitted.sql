-- USER_SUBMITTED source added to support Phase 2+ (user name submissions feature).
-- This migration introduces the new source value to the names.source CHECK constraint.
-- No rows use this source yet, so this is a zero-impact schema change safe to deploy early.
-- Inline column CHECKs from V1 are auto-named <table>_<column>_check.
ALTER TABLE names DROP CONSTRAINT names_source_check;
ALTER TABLE names ADD CONSTRAINT names_source_check
    CHECK (source IN ('CURATED', 'AI_GENERATED', 'AI_REFINED', 'USER_SUBMITTED'));
