-- Robin DKIM schema (PostgreSQL)
-- Safe to run multiple times.

CREATE TABLE IF NOT EXISTS dkim_keys (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(253) NOT NULL,
    selector VARCHAR(63) NOT NULL,
    algorithm VARCHAR(10) NOT NULL,
    private_key_enc TEXT NOT NULL,
    public_key TEXT NOT NULL,
    dns_record_value TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    test_mode BOOLEAN DEFAULT TRUE,
    strategy VARCHAR(20),
    service_tag VARCHAR(63),
    paired_key_id BIGINT,
    rotation_scheduled_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    retire_after TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_dkim_keys_domain_selector UNIQUE (domain, selector),
    CONSTRAINT fk_dkim_keys_paired
        FOREIGN KEY (paired_key_id) REFERENCES dkim_keys(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS dkim_rotation_events (
    id BIGSERIAL PRIMARY KEY,
    key_id BIGINT,
    event_type VARCHAR(30) NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20),
    notes TEXT,
    triggered_by VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT fk_dkim_rotation_events_key
        FOREIGN KEY (key_id) REFERENCES dkim_keys(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS dkim_detected_selectors (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(253) NOT NULL,
    selector VARCHAR(63) NOT NULL,
    public_key_dns TEXT,
    algorithm VARCHAR(10),
    test_mode BOOLEAN,
    revoked BOOLEAN DEFAULT FALSE,
    detected_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_dkim_detected_selectors_domain_selector UNIQUE (domain, selector)
);

CREATE INDEX IF NOT EXISTS idx_dkim_keys_domain ON dkim_keys(domain, status);
CREATE INDEX IF NOT EXISTS idx_dkim_keys_active ON dkim_keys(domain, status) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_dkim_rotation_events_key ON dkim_rotation_events(key_id, created_at DESC);
