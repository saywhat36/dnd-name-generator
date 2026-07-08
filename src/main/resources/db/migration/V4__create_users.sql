CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    username_norm VARCHAR(64)  NOT NULL,   -- lowercased, mirrors names.normalized_name pattern
    password_hash VARCHAR(255) NOT NULL,   -- delegating encoder {bcrypt} prefix fits in 255
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username_norm UNIQUE (username_norm)
);

-- favorites.owner_id was left bare (plain BIGINT, no FK) in V1 because the users
-- table did not exist yet -- now that it does, wire up the referential integrity.
ALTER TABLE favorites
    ADD CONSTRAINT fk_favorites_owner FOREIGN KEY (owner_id) REFERENCES users (id);

-- Postgres auto-indexes the referenced side (users.id PK) but never the referencing
-- column, so back owner_id explicitly -- same precedent as idx_favorites_name_id in V1.
-- Without it, users->favorites joins and the FK check on user deletion seq-scan favorites.
CREATE INDEX idx_favorites_owner_id ON favorites (owner_id);
