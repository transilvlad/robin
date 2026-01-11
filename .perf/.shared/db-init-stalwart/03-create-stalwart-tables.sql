-- Stalwart directory tables (PostgreSQL-backed authentication)

-- Accounts table: stores user and group accounts.
CREATE TABLE IF NOT EXISTS accounts (
    name TEXT PRIMARY KEY,
    secret TEXT NOT NULL,
    description TEXT,
    type TEXT NOT NULL CHECK (type IN ('individual', 'group', 'admin', 'list', 'other')),
    quota BIGINT DEFAULT 0,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Emails table: maps email addresses to accounts.
CREATE TABLE IF NOT EXISTS emails (
    name TEXT NOT NULL REFERENCES accounts(name) ON DELETE CASCADE,
    address TEXT NOT NULL,
    type TEXT DEFAULT 'primary' CHECK (type IN ('primary', 'alias', 'list')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (name, address)
);

-- Group members table: tracks group memberships.
CREATE TABLE IF NOT EXISTS group_members (
    name TEXT NOT NULL REFERENCES accounts(name) ON DELETE CASCADE,
    member_of TEXT NOT NULL REFERENCES accounts(name) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (name, member_of)
);

-- Indexes for performance.
CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name);
CREATE INDEX IF NOT EXISTS idx_accounts_active ON accounts(active) WHERE active = true;
CREATE INDEX IF NOT EXISTS idx_emails_address ON emails(address);
CREATE INDEX IF NOT EXISTS idx_emails_name ON emails(name);
CREATE INDEX IF NOT EXISTS idx_emails_type ON emails(type);
CREATE INDEX IF NOT EXISTS idx_group_members_name ON group_members(name);
CREATE INDEX IF NOT EXISTS idx_group_members_member_of ON group_members(member_of);
