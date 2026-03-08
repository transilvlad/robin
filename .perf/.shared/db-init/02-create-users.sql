-- Sustained perf users. Every account uses the same benchmark password: benchmark
-- The benchmark runner delivers across this whole recipient set and verifies cumulative mailbox counts.
INSERT INTO users (email, password, uid, gid, home, maildir, active) VALUES
    ('tony@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/tony', 'maildir:/var/mail/vhosts/example.com/tony', true),
    ('pepper@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/pepper', 'maildir:/var/mail/vhosts/example.com/pepper', true),
    ('happy@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/happy', 'maildir:/var/mail/vhosts/example.com/happy', true),
    ('rhodey@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/rhodey', 'maildir:/var/mail/vhosts/example.com/rhodey', true),
    ('natasha@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/natasha', 'maildir:/var/mail/vhosts/example.com/natasha', true),
    ('bruce@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/bruce', 'maildir:/var/mail/vhosts/example.com/bruce', true),
    ('steve@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/steve', 'maildir:/var/mail/vhosts/example.com/steve', true),
    ('thor@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/thor', 'maildir:/var/mail/vhosts/example.com/thor', true),
    ('clint@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/clint', 'maildir:/var/mail/vhosts/example.com/clint', true),
    ('wanda@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/wanda', 'maildir:/var/mail/vhosts/example.com/wanda', true),
    ('vision@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/vision', 'maildir:/var/mail/vhosts/example.com/vision', true),
    ('peter@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/peter', 'maildir:/var/mail/vhosts/example.com/peter', true),
    ('scott@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/scott', 'maildir:/var/mail/vhosts/example.com/scott', true),
    ('hope@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/hope', 'maildir:/var/mail/vhosts/example.com/hope', true),
    ('carol@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/carol', 'maildir:/var/mail/vhosts/example.com/carol', true),
    ('sam@example.com', '{PLAIN}benchmark', 5000, 5000, '/var/mail/vhosts/example.com/sam', 'maildir:/var/mail/vhosts/example.com/sam', true)
ON CONFLICT (email) DO NOTHING;

-- Test aliases
INSERT INTO aliases (source, destination, active) VALUES
    ('admin@example.com', 'tony@example.com', true),
    ('postmaster@example.com', 'tony@example.com', true)
ON CONFLICT (source, destination) DO NOTHING;
