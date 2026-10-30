-- Explicit, additive migration. Rehearse on a restored copy and back up first.
-- Never normalise existing identities implicitly or overwrite operator policy.
\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM users WHERE email <> lower(email) OR email !~ '^[^@[:space:]]+@[^@[:space:]]+$') THEN
    RAISE EXCEPTION 'Review and normalise existing users.email before enabling managed domains';
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS mail_domains (
  domain VARCHAR(253) PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT true,
  plus_addressing BOOLEAN NOT NULL DEFAULT false,
  catch_all VARCHAR(320),
  CHECK (domain = lower(domain) AND domain ~ '^[a-z0-9][a-z0-9.-]*[a-z0-9]$'),
  CHECK (catch_all IS NULL OR catch_all = lower(catch_all)),
  FOREIGN KEY (catch_all) REFERENCES users(email) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS mail_sender_grants (
  account VARCHAR(320) NOT NULL REFERENCES users(email) ON UPDATE CASCADE ON DELETE CASCADE,
  address VARCHAR(320) NOT NULL,
  grant_type VARCHAR(16) NOT NULL CHECK (grant_type IN ('primary', 'alias', 'delegated')),
  PRIMARY KEY (account, address),
  CHECK (account = lower(account) AND address = lower(address)),
  CHECK (address ~ '^[^@[:space:]]+@[^@[:space:]]+$'),
  CHECK (grant_type <> 'primary' OR account = address)
);

-- A grant to a deleted local identity must not survive reuse of that address.
CREATE OR REPLACE FUNCTION revoke_removed_mail_address() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  removed_address TEXT;
BEGIN
  IF TG_TABLE_NAME = 'users' THEN
    removed_address := OLD.email;
    IF TG_OP = 'UPDATE' AND NEW.email = OLD.email THEN
      RETURN NEW;
    END IF;
    DELETE FROM mail_sender_grants WHERE account = removed_address OR address = removed_address;
    IF TG_OP = 'DELETE' THEN
      RETURN OLD;
    END IF;
    RETURN NEW;
  ELSE
    removed_address := OLD.source;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users WHERE email = removed_address)
     AND NOT EXISTS (SELECT 1 FROM aliases WHERE source = removed_address) THEN
    DELETE FROM mail_sender_grants WHERE address = removed_address;
  END IF;
  RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS revoke_removed_user_address ON users;
CREATE TRIGGER revoke_removed_user_address BEFORE DELETE OR UPDATE OF email ON users
  FOR EACH ROW EXECUTE FUNCTION revoke_removed_mail_address();
DROP TRIGGER IF EXISTS revoke_removed_alias_address ON aliases;
CREATE TRIGGER revoke_removed_alias_address AFTER DELETE OR UPDATE OF source ON aliases
  FOR EACH ROW EXECUTE FUNCTION revoke_removed_mail_address();

INSERT INTO mail_domains (domain)
  SELECT DISTINCT split_part(email, '@', 2) FROM users
  ON CONFLICT (domain) DO NOTHING;
INSERT INTO mail_sender_grants (account, address, grant_type)
  SELECT email, email, 'primary' FROM users
  ON CONFLICT (account, address) DO NOTHING;

GRANT SELECT ON mail_domains, mail_sender_grants TO robin_mta, robin_mailbox;
GRANT SELECT, INSERT, UPDATE, DELETE ON mail_domains, mail_sender_grants TO robin_admin;
COMMIT;
