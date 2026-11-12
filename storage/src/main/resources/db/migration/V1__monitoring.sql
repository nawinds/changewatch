CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sources (
    id UUID PRIMARY KEY,
    spreadsheet_id VARCHAR(200) NOT NULL,
    sheet_id BIGINT NOT NULL CHECK (sheet_id >= 0),
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds BETWEEN 30 AND 86400),
    next_check_at TIMESTAMPTZ NOT NULL,
    last_checked_at TIMESTAMPTZ,
    last_error TEXT,
    UNIQUE (spreadsheet_id, sheet_id)
);

CREATE TABLE monitors (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES accounts(id),
    source_id UUID NOT NULL REFERENCES sources(id),
    name VARCHAR(200) NOT NULL,
    row_key VARCHAR(200) NOT NULL,
    row_value VARCHAR(200) NOT NULL,
    columns JSONB NOT NULL CHECK (jsonb_typeof(columns) = 'array'),
    rule VARCHAR(30) NOT NULL CHECK (rule IN ('ANY_CHANGE', 'VALUE_APPEARED', 'INCREASED', 'DECREASED')),
    active BOOLEAN NOT NULL DEFAULT true,
    initialized BOOLEAN NOT NULL DEFAULT false,
    values JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE changes (
    id UUID PRIMARY KEY,
    monitor_id UUID NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    column_name VARCHAR(200) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    type VARCHAR(30) NOT NULL CHECK (type IN ('VALUE_APPEARED', 'VALUE_REMOVED', 'VALUE_CHANGED')),
    matches_rule BOOLEAN NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    CHECK (old_value IS DISTINCT FROM new_value)
);
CREATE INDEX sources_due_idx ON sources(next_check_at);
CREATE INDEX monitors_source_idx ON monitors(source_id) WHERE active;
CREATE INDEX monitors_owner_idx ON monitors(owner_id, created_at, id);
CREATE INDEX changes_history_idx ON changes(monitor_id, detected_at DESC, id DESC);
