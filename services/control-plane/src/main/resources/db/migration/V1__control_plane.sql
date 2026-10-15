-- Veil control plane: identity, configuration and its history.

CREATE TABLE orgs (
    id                     TEXT PRIMARY KEY,
    name                   TEXT        NOT NULL,
    -- Salt for value fingerprints in the audit log (so the same Aadhaar can be correlated, never stored).
    fingerprint_salt       TEXT        NOT NULL,
    active_policy_version  INT         NOT NULL DEFAULT 0,
    config_version         BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE teams (
    id                  TEXT        NOT NULL,
    org_id              TEXT        NOT NULL REFERENCES orgs (id),
    name                TEXT        NOT NULL,
    daily_budget_micros BIGINT      NOT NULL DEFAULT 0 CHECK (daily_budget_micros >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, id),
    UNIQUE (org_id, name)
);

CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    org_id        TEXT        NOT NULL REFERENCES orgs (id),
    email         TEXT        NOT NULL UNIQUE,
    name          TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only the SHA-256 of a key is stored; the key is shown once, when created.
CREATE TABLE virtual_keys (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT        NOT NULL REFERENCES orgs (id),
    team_id             TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    kind                TEXT        NOT NULL CHECK (kind IN ('API', 'EXTENSION')),
    key_hash            TEXT        NOT NULL UNIQUE,
    prefix              TEXT        NOT NULL,
    last4               TEXT        NOT NULL,
    rpm_limit           INT         NOT NULL DEFAULT 0 CHECK (rpm_limit >= 0),
    daily_budget_micros BIGINT      NOT NULL DEFAULT 0 CHECK (daily_budget_micros >= 0),
    created_by          TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    FOREIGN KEY (org_id, team_id) REFERENCES teams (org_id, id)
);
CREATE INDEX virtual_keys_org ON virtual_keys (org_id, created_at DESC);

-- Policies are immutable versions; publishing creates a new one and moves orgs.active_policy_version.
CREATE TABLE policy_versions (
    org_id     TEXT        NOT NULL REFERENCES orgs (id),
    version    INT         NOT NULL,
    policy     JSONB       NOT NULL,
    note       TEXT,
    created_by TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, version)
);

CREATE TABLE providers (
    id                 TEXT        NOT NULL,
    org_id             TEXT        NOT NULL REFERENCES orgs (id),
    name               TEXT        NOT NULL,
    kind               TEXT        NOT NULL CHECK (kind IN ('ECHO', 'OPENAI_COMPATIBLE', 'ANTHROPIC')),
    base_url           TEXT,
    -- AES-256-GCM ciphertext of the provider's API key; the API never returns it.
    api_key_ciphertext TEXT,
    local              BOOLEAN     NOT NULL DEFAULT false,
    enabled            BOOLEAN     NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, id)
);

CREATE TABLE routes (
    org_id              TEXT    NOT NULL REFERENCES orgs (id),
    model               TEXT    NOT NULL,
    targets             JSONB   NOT NULL,
    input_usd_per_mtok  NUMERIC NOT NULL DEFAULT 0,
    output_usd_per_mtok NUMERIC NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, model)
);

CREATE TABLE dictionary_terms (
    id         BIGSERIAL PRIMARY KEY,
    org_id     TEXT        NOT NULL REFERENCES orgs (id),
    term       TEXT        NOT NULL,
    label      TEXT        NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX dictionary_terms_unique ON dictionary_terms (org_id, lower(term));

CREATE TABLE topic_examples (
    id          BIGSERIAL PRIMARY KEY,
    org_id      TEXT NOT NULL REFERENCES orgs (id),
    label       TEXT NOT NULL,
    description TEXT NOT NULL,
    example     TEXT NOT NULL
);

-- Who changed what, for auditors. Never contains secrets.
CREATE TABLE admin_audit (
    id     BIGSERIAL PRIMARY KEY,
    org_id TEXT        NOT NULL,
    actor  TEXT        NOT NULL,
    action TEXT        NOT NULL,
    target TEXT,
    detail TEXT,
    at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_org ON admin_audit (org_id, at DESC);

CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    topic        TEXT        NOT NULL,
    event_key    TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
