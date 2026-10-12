-- Empty mailbox schema. Production deployments provision their own users.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  uid INTEGER NOT NULL DEFAULT 5000,
  gid INTEGER NOT NULL DEFAULT 5000,
  home TEXT,
  maildir TEXT
);
CREATE TABLE aliases (
  id SERIAL PRIMARY KEY,
  source TEXT NOT NULL,
  destination TEXT NOT NULL
);
CREATE TABLE auto_aliases (
  id SERIAL PRIMARY KEY,
  source TEXT NOT NULL,
  destination TEXT NOT NULL
);
CREATE INDEX idx_aliases_source ON aliases(source);
CREATE INDEX idx_aliases_destination ON aliases(destination);
CREATE INDEX idx_auto_aliases_source ON auto_aliases(source);
CREATE INDEX idx_auto_aliases_destination ON auto_aliases(destination);

GRANT SELECT ON users, aliases, auto_aliases TO robin_mailbox, robin_mta;
GRANT SELECT, INSERT, UPDATE, DELETE ON users, aliases, auto_aliases TO robin_admin;
GRANT USAGE, SELECT ON SEQUENCE users_id_seq, aliases_id_seq, auto_aliases_id_seq TO robin_admin;
