-- Test-only account database shared by Robin and Dovecot.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
  id SERIAL PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  uid INTEGER NOT NULL DEFAULT 5000,
  gid INTEGER NOT NULL DEFAULT 5000,
  home TEXT,
  maildir TEXT
);

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

CREATE INDEX IF NOT EXISTS idx_aliases_source ON aliases(source);
CREATE INDEX IF NOT EXISTS idx_aliases_destination ON aliases(destination);
CREATE INDEX IF NOT EXISTS idx_auto_aliases_source ON auto_aliases(source);
CREATE INDEX IF NOT EXISTS idx_auto_aliases_destination ON auto_aliases(destination);

DO $$
DECLARE
  h TEXT;
BEGIN
  h := crypt('stark', '$6$sT0nYsalt2025$');
  IF left(h, 3) <> '$6$' THEN
    RAISE EXCEPTION 'crypt() did not produce a SHA512-CRYPT ($6) hash';
  END IF;
  INSERT INTO users (email, password, uid, gid, home, maildir)
  VALUES ('tony@example.com', '{SHA512-CRYPT}' || h, 5000, 5000,
          '/var/mail/vhosts/example.com/tony', 'maildir:/var/mail/vhosts/example.com/tony')
  ON CONFLICT (email) DO NOTHING;
END $$;

DO $$
DECLARE
  h TEXT;
BEGIN
  h := crypt('potts', '$6$p3pp3rsalt2025$');
  IF left(h, 3) <> '$6$' THEN
    RAISE EXCEPTION 'crypt() did not produce a SHA512-CRYPT ($6) hash';
  END IF;
  INSERT INTO users (email, password, uid, gid, home, maildir)
  VALUES ('pepper@example.com', '{SHA512-CRYPT}' || h, 5000, 5000,
          '/var/mail/vhosts/example.com/pepper', 'maildir:/var/mail/vhosts/example.com/pepper')
  ON CONFLICT (email) DO NOTHING;
END $$;

DO $$
DECLARE
  h TEXT;
BEGIN
  h := crypt('hogan', '$6$h0gansalt2025$');
  IF left(h, 3) <> '$6$' THEN
    RAISE EXCEPTION 'crypt() did not produce a SHA512-CRYPT ($6) hash';
  END IF;
  INSERT INTO users (email, password, uid, gid, home, maildir)
  VALUES ('happy@example.com', '{SHA512-CRYPT}' || h, 5000, 5000,
          '/var/mail/vhosts/example.com/happy', 'maildir:/var/mail/vhosts/example.com/happy')
  ON CONFLICT (email) DO NOTHING;
END $$;

INSERT INTO aliases (source, destination)
VALUES ('tony-alias@example.com', 'tony@example.com')
ON CONFLICT DO NOTHING;

INSERT INTO auto_aliases (source, destination)
VALUES ('tony@', 'tony@example.com')
ON CONFLICT DO NOTHING;
