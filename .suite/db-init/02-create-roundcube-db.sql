-- Create separate database for Roundcube
CREATE DATABASE roundcube OWNER robin_roundcube;
REVOKE ALL ON DATABASE roundcube FROM PUBLIC;
