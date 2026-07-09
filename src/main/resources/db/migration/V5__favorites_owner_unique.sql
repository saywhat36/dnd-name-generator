-- Roadmap Phase 2: "(owner_id, name_id) unique constraint on favorites". Needed now that
-- FavoriteService.addFavorite branches to an owner-keyed insert for authenticated identities --
-- without this, the idempotent-add's find-then-insert has no DB-level guarantee against a
-- concurrent duplicate the way uq_favorites_session_name already gives the session-keyed path.
-- A plain UNIQUE constraint is safe here even though owner_id is nullable: Postgres treats NULLs
-- as distinct for uniqueness purposes, so anonymous rows (owner_id IS NULL) never collide with
-- each other under this constraint -- only two rows sharing the same non-null owner_id do.
ALTER TABLE favorites
    ADD CONSTRAINT uq_favorites_owner_name UNIQUE (owner_id, name_id);
