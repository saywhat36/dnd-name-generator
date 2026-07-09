-- Roadmap Phase 2: "(owner_id, name_id) unique constraint on favorites". Needed now that every
-- favorite write is owner-keyed (see docs/DECISIONS.md, identity resolution slice revision) --
-- without this, FavoriteService.addFavorite's idempotent find-then-insert has no DB-level
-- guarantee against a concurrent duplicate. A plain UNIQUE constraint is correct here even
-- though the column predates this constraint being nullable in V1: Postgres treats NULLs as
-- distinct for uniqueness purposes, so any pre-existing session-keyed rows (owner_id IS NULL,
-- now orphaned dead data -- nothing in the app writes or reads them anymore) never collide with
-- each other or with owner-keyed rows under this constraint.
ALTER TABLE favorites
    ADD CONSTRAINT uq_favorites_owner_name UNIQUE (owner_id, name_id);
