-- Sustained perf users with a shared benchmark password hash.
-- Plain-text benchmark password: benchmark

-- tony@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('tony@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Tony Stark',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('tony@example.com', 'tony@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- pepper@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('pepper@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Pepper Potts',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('pepper@example.com', 'pepper@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- happy@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('happy@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Happy Hogan',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('happy@example.com', 'happy@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- rhodey@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('rhodey@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'James Rhodes',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('rhodey@example.com', 'rhodey@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- natasha@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('natasha@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Natasha Romanoff',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('natasha@example.com', 'natasha@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- bruce@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('bruce@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Bruce Banner',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('bruce@example.com', 'bruce@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- steve@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('steve@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Steve Rogers',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('steve@example.com', 'steve@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- thor@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('thor@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Thor Odinson',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('thor@example.com', 'thor@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- clint@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('clint@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Clint Barton',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('clint@example.com', 'clint@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- wanda@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('wanda@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Wanda Maximoff',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('wanda@example.com', 'wanda@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- vision@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('vision@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Vision',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('vision@example.com', 'vision@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- peter@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('peter@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Peter Parker',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('peter@example.com', 'peter@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- scott@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('scott@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Scott Lang',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('scott@example.com', 'scott@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- hope@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('hope@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Hope van Dyne',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('hope@example.com', 'hope@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- carol@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('carol@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Carol Danvers',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('carol@example.com', 'carol@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- sam@example.com
INSERT INTO accounts (name, secret, description, type, quota, active) VALUES
    ('sam@example.com',
     '$6$robinperf$VB.gop5u3QbJSw0pS8yYEFTdlOgZaA7FLk9AZdxUo52M1R2LdJTNd.uFeCN.QscWPWUdfRzu.zONvD4BYJLxU0',
     'Sam Wilson',
     'individual',
     0,
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO emails (name, address, type) VALUES
    ('sam@example.com', 'sam@example.com', 'primary')
ON CONFLICT (name, address) DO NOTHING;

-- Aliases for testing (admin and postmaster point to tony).
INSERT INTO emails (name, address, type) VALUES
    ('tony@example.com', 'admin@example.com', 'alias'),
    ('tony@example.com', 'postmaster@example.com', 'alias')
ON CONFLICT (name, address) DO NOTHING;
