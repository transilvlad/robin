-- Create test users with SHA-512 crypt password hashes.
-- These are the same users as Dovecot tests for consistency.

-- tony@example.com / stark
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('tony@example.com',
     '$6$hqscjv9CVqskVbBD$crQH9FY4k3NjcQKfp8fOJVA5MZ6JtVzoygGKGR6qyvOqmtX/2xNNlvdAmkNTPP5pDjVAw.0JRHQxdVq7hIghf0',
     'Tony Stark',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('tony@example.com', 'tony@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- pepper@example.com / potts
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('pepper@example.com',
     '$6$1pR3ex2d8v83s2/b$rNJ5DYfgsc8pm/5zOCrgEE5SYInKwXsbn/djt0NNgf1LYBLDaIbwva/OXNYTlYFl2sVpL/OVLwDjkdq/KrGow.',
     'Pepper Potts',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('pepper@example.com', 'pepper@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- happy@example.com / hogan
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('happy@example.com',
     '$6$23mzvRS9AH41mVCm$C8N97/fZucdKBrwDS7UwCFBrQDVNEsm6sciJspnt5VWE60QjePBfYtwnhIc9YyKsMUdem7Sdu0slcK1Hisekb/',
     'Happy Hogan',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('happy@example.com', 'happy@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- Aliases for testing (admin and postmaster point to tony).
INSERT INTO emails (name, address, type) VALUES
    ('tony@example.com', 'admin@example.com', 'alias'),
    ('tony@example.com', 'postmaster@example.com', 'alias')
ON CONFLICT (name, address) DO NOTHING;
