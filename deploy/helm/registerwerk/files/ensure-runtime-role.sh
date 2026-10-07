#!/bin/sh
# Idempotent creation/refresh of the RUNTIME database login (T6-17).
#
# Two logins, two jobs:
#   * migrator / owner  = $POSTGRES_USER (DB_USER in compose). Owns the schema; used ONLY by Flyway
#                         (spring.flyway.user / password in the backend).
#   * runtime           = $DB_APP_USER (default registerwerk_app, the role name V1/V36 already
#                         assume). DML only: no ownership, no DDL, and V36/V57 withhold
#                         UPDATE/DELETE/TRUNCATE on audit_event and its partitions, so the
#                         audit WORM defences actually bind the application.
#
# The role is created here (before Flyway ever runs) because a database cannot authenticate a
# role it does not have. Grants live in migration V57 so every environment gets identical privileges.
# Safe to re-run on every `docker compose up` (it also rotates the password). Not mounted into
# /docker-entrypoint-initdb.d: it is executed by the one-shot `db-roles` compose service so it also
# works against an existing data volume.
#
# Env: POSTGRES_USER, POSTGRES_DB (default registerwerk), DB_APP_USER (default registerwerk_app),
#      DB_APP_PASSWORD (required); PGHOST / PGPASSWORD for a remote server.
set -eu

: "${POSTGRES_USER:?POSTGRES_USER (the schema owner / migrator login) must be set}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD (runtime login password) must be set}"
POSTGRES_DB="${POSTGRES_DB:-registerwerk}"
DB_APP_USER="${DB_APP_USER:-registerwerk_app}"

if [ "$DB_APP_USER" = "$POSTGRES_USER" ]; then
  echo "refusing: the runtime login ($DB_APP_USER) must differ from the schema owner ($POSTGRES_USER)" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="$DB_APP_USER" <<'SQL'
\getenv app_password DB_APP_PASSWORD
SELECT format('CREATE ROLE %I', :'app_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
              :'app_user', :'app_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SQL
echo "runtime login '$DB_APP_USER' ready (schema owner / migrator: '$POSTGRES_USER')"
