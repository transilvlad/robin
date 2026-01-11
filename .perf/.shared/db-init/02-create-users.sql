-- Test users (same as .suite, but with PLAIN passwords for performance testing)
INSERT INTO users (email, password, uid, gid, home, maildir, active) VALUES
    ('tony@example.com', '{PLAIN}stark', 5000, 5000, '/var/mail/vhosts/example.com/tony', 'maildir:/var/mail/vhosts/example.com/tony', true),
    ('pepper@example.com', '{PLAIN}potts', 5000, 5000, '/var/mail/vhosts/example.com/pepper', 'maildir:/var/mail/vhosts/example.com/pepper', true),
    ('happy@example.com', '{PLAIN}hogan', 5000, 5000, '/var/mail/vhosts/example.com/happy', 'maildir:/var/mail/vhosts/example.com/happy', true)
ON CONFLICT (email) DO NOTHING;

-- Test aliases
INSERT INTO aliases (source, destination, active) VALUES
    ('admin@example.com', 'tony@example.com', true),
    ('postmaster@example.com', 'tony@example.com', true)
ON CONFLICT (source, destination) DO NOTHING;
