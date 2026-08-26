-- Creates the chaincache role + database on first init of a fresh postgres data volume, so
-- chaincache-sepolia/chaincache-base (the optional chaincache-true profile) can share this one
-- PostgreSQL instance with Registerwerk's own `registerwerk` database, instead of running a
-- second dedicated Postgres container — mirroring how a single managed instance (e.g. Cloud SQL
-- on GKE) hosts multiple databases in production. Runs unconditionally: harmless if the
-- chaincache profile is never enabled (an unused empty database), and postgres-entrypoint scripts
-- under /docker-entrypoint-initdb.d run once, only against a genuinely empty data directory — see
-- docker-compose.yml's postgres service comment and docs/operator/installation/docker.md for what
-- that means for an *existing* deployment's already-populated volume.
CREATE USER chaincache WITH PASSWORD 'chaincache';
CREATE DATABASE chaincache OWNER chaincache;
