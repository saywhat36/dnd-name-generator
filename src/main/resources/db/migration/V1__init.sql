CREATE TABLE generation_log (
    id                        BIGSERIAL PRIMARY KEY,
    ts                        TIMESTAMPTZ         NOT NULL DEFAULT now(),
    race                      VARCHAR(32)         NOT NULL,
    gender                    VARCHAR(16)         NOT NULL CHECK (gender IN ('MASCULINE', 'FEMININE')),
    mode                      VARCHAR(16)         NOT NULL CHECK (mode IN ('STANDARD', 'REFINEMENT')),
    provider                  VARCHAR(64),
    model                     VARCHAR(128),
    prompt_version            VARCHAR(32),
    raw_response              TEXT,
    names_requested           INTEGER,
    names_accepted            INTEGER,
    names_rejected_duplicate  INTEGER,
    names_rejected_quality    INTEGER,
    parse_success             BOOLEAN             NOT NULL,
    error_message             TEXT
);

CREATE TABLE names (
    id                 BIGSERIAL PRIMARY KEY,
    display_name        VARCHAR(128)        NOT NULL,
    normalized_name      VARCHAR(128)        NOT NULL,
    race                VARCHAR(32)         NOT NULL CHECK (race IN
        ('ELF', 'DWARF', 'HUMAN', 'HALFLING', 'ORC', 'GNOME', 'DRAGONBORN', 'TIEFLING')),
    gender               VARCHAR(16)         NOT NULL CHECK (gender IN ('MASCULINE', 'FEMININE')),
    source               VARCHAR(16)         NOT NULL CHECK (source IN
        ('CURATED', 'AI_GENERATED', 'AI_REFINED')),
    status               VARCHAR(16)         NOT NULL DEFAULT 'ACTIVE' CHECK (status IN
        ('ACTIVE', 'FLAGGED', 'REJECTED')),
    provider             VARCHAR(64),
    model                VARCHAR(128),
    prompt_version       VARCHAR(32),
    generation_log_id    BIGINT              REFERENCES generation_log (id),
    created_at           TIMESTAMPTZ         NOT NULL DEFAULT now(),
    CONSTRAINT uq_names_normalized_race_gender UNIQUE (normalized_name, race, gender)
);

-- The unique index above leads with normalized_name, so it can't serve the
-- read path's filter (race, gender, status, source) -- add a dedicated index.
CREATE INDEX idx_names_serving ON names (race, gender, status, source);

CREATE TABLE favorites (
    id          BIGSERIAL PRIMARY KEY,
    name_id     BIGINT      NOT NULL REFERENCES names (id),
    session_id  VARCHAR(64),
    owner_id    BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_favorites_session_name UNIQUE (session_id, name_id),
    CONSTRAINT chk_favorites_owner_present CHECK (session_id IS NOT NULL OR owner_id IS NOT NULL)
);

CREATE TABLE name_reports (
    id          BIGSERIAL PRIMARY KEY,
    name_id     BIGINT      NOT NULL REFERENCES names (id),
    session_id  VARCHAR(64) NOT NULL,
    reason      VARCHAR(256),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_name_reports_session_name UNIQUE (session_id, name_id)
);
