-- Dovecot users table and sample data for Postgres init
-- Simplified version for suite testing with plain passwords (hashed by Dovecot)

CREATE TABLE IF NOT EXISTS users (
  id SERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  uid INTEGER NOT NULL DEFAULT 5000,
  gid INTEGER NOT NULL DEFAULT 5000,
  home TEXT,
  maildir TEXT
);

-- Alias tables
CREATE TABLE IF NOT EXISTS aliases (
  id SERIAL PRIMARY KEY,
  source TEXT NOT NULL,
  destination TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auto_aliases (
  id SERIAL PRIMARY KEY,
  source TEXT NOT NULL,
  destination TEXT NOT NULL
);

-- Add indexes to speed up alias lookups
CREATE INDEX IF NOT EXISTS idx_aliases_source ON aliases(source);
CREATE INDEX IF NOT EXISTS idx_aliases_destination ON aliases(destination);
CREATE INDEX IF NOT EXISTS idx_auto_aliases_source ON auto_aliases(source);
CREATE INDEX IF NOT EXISTS idx_auto_aliases_destination ON auto_aliases(destination);

-- Insert users with plain passwords (Dovecot will handle hashing)
-- Using {PLAIN} scheme which Dovecot understands
INSERT INTO users (email, password, uid, gid, home, maildir)
VALUES
  ('tony@example.com', '{PLAIN}stark', 5000, 5000, '/var/mail/vhosts/example.com/tony', 'maildir:/var/mail/vhosts/example.com/tony'),
  ('pepper@example.com', '{PLAIN}potts', 5000, 5000, '/var/mail/vhosts/example.com/pepper', 'maildir:/var/mail/vhosts/example.com/pepper'),
  ('happy@example.com', '{PLAIN}hogan', 5000, 5000, '/var/mail/vhosts/example.com/happy', 'maildir:/var/mail/vhosts/example.com/happy')
ON CONFLICT (email) DO NOTHING;

-- Sample aliases
INSERT INTO aliases (source, destination)
VALUES ('tony-alias@example.com', 'tony@example.com')
ON CONFLICT DO NOTHING;

-- Sample auto alias
INSERT INTO auto_aliases (source, destination)
VALUES ('tony@', 'tony@example.com')
ON CONFLICT DO NOTHING;
