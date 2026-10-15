-- Veil insight: the audit log, rollups and incidents.

-- One row per request. Partitioned by day, so retention is DROP PARTITION (instant) rather than a huge DELETE,
-- and time-range queries only touch the partitions they need. The primary key includes the partition key, which
-- also makes redelivered Kafka events idempotent (ON CONFLICT DO NOTHING).
CREATE TABLE requests (
    id              TEXT        NOT NULL,
    at              TIMESTAMPTZ NOT NULL,
    org_id          TEXT        NOT NULL,
    key_id          TEXT,
    team_id         TEXT,
    team_name       TEXT,
    actor           TEXT,
    source          TEXT        NOT NULL,
    kind            TEXT        NOT NULL,
    site            TEXT,
    model           TEXT,
    provider        TEXT,
    action          TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    reasons         JSONB       NOT NULL DEFAULT '[]',
    findings        JSONB       NOT NULL DEFAULT '[]',
    finding_count   INT         NOT NULL DEFAULT 0,
    top_severity    TEXT,
    types           TEXT[]      NOT NULL DEFAULT '{}',
    redacted_prompt TEXT,
    prompt_chars    INT         NOT NULL DEFAULT 0,
    tokens_in       INT         NOT NULL DEFAULT 0,
    tokens_out      INT         NOT NULL DEFAULT 0,
    cost_micros     BIGINT      NOT NULL DEFAULT 0,
    latency_ms      BIGINT      NOT NULL DEFAULT 0,
    detect_micros   BIGINT      NOT NULL DEFAULT 0,
    upstream_ms     BIGINT      NOT NULL DEFAULT 0,
    cache_hit       BOOLEAN     NOT NULL DEFAULT false,
    stream          BOOLEAN     NOT NULL DEFAULT false,
    policy_version  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);

-- Catches anything outside the pre-created daily partitions (clock skew, very late events).
CREATE TABLE requests_default PARTITION OF requests DEFAULT;

CREATE INDEX requests_org_at ON requests (org_id, at DESC);
CREATE INDEX requests_org_action_at ON requests (org_id, action, at DESC);
CREATE INDEX requests_types ON requests USING GIN (types);

-- Per-minute rollups: dashboards read these instead of scanning raw requests.
CREATE TABLE usage_minute (
    org_id         TEXT        NOT NULL,
    minute         TIMESTAMPTZ NOT NULL,
    team_id        TEXT        NOT NULL,
    source         TEXT        NOT NULL,
    model          TEXT        NOT NULL,
    requests       BIGINT      NOT NULL DEFAULT 0,
    blocked        BIGINT      NOT NULL DEFAULT 0,
    redacted       BIGINT      NOT NULL DEFAULT 0,
    warned         BIGINT      NOT NULL DEFAULT 0,
    routed_local   BIGINT      NOT NULL DEFAULT 0,
    cache_hits     BIGINT      NOT NULL DEFAULT 0,
    errors         BIGINT      NOT NULL DEFAULT 0,
    findings       BIGINT      NOT NULL DEFAULT 0,
    protected      BIGINT      NOT NULL DEFAULT 0,
    tokens_in      BIGINT      NOT NULL DEFAULT 0,
    tokens_out     BIGINT      NOT NULL DEFAULT 0,
    cost_micros    BIGINT      NOT NULL DEFAULT 0,
    latency_sum_ms BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, minute, team_id, source, model)
);

CREATE TABLE finding_minute (
    org_id  TEXT        NOT NULL,
    minute  TIMESTAMPTZ NOT NULL,
    type    TEXT        NOT NULL,
    team_id TEXT        NOT NULL,
    action  TEXT        NOT NULL,
    count   BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, minute, type, team_id, action)
);

CREATE TABLE incidents (
    id           BIGSERIAL PRIMARY KEY,
    org_id       TEXT        NOT NULL,
    request_id   TEXT        NOT NULL,
    first_at     TIMESTAMPTZ NOT NULL,
    last_at      TIMESTAMPTZ NOT NULL,
    occurrences  INT         NOT NULL DEFAULT 1,
    severity     TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    team_id      TEXT,
    team_name    TEXT,
    actor        TEXT,
    key_id       TEXT,
    source       TEXT        NOT NULL,
    site         TEXT,
    types        TEXT[]      NOT NULL,
    action       TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'FALSE_POSITIVE')),
    assignee     TEXT,
    notes        TEXT,
    explanation  TEXT,
    explained_by TEXT,
    fingerprints TEXT[]      NOT NULL DEFAULT '{}',
    dedupe_key   TEXT        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX incidents_org_status ON incidents (org_id, status, last_at DESC);
CREATE INDEX incidents_dedupe ON incidents (org_id, dedupe_key, last_at DESC);
