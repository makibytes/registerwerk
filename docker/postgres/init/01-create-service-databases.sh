#!/bin/sh
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${KONG_DB_USER}') THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', '${KONG_DB_USER}', '${KONG_DB_PASSWORD}');
    ELSE
      EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', '${KONG_DB_USER}', '${KONG_DB_PASSWORD}');
    END IF;
  END
  \$\$;

  SELECT format('CREATE DATABASE kong OWNER %I', '${KONG_DB_USER}')
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kong')\gexec

  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${KONGA_DB_USER}') THEN
      EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', '${KONGA_DB_USER}', '${KONGA_DB_PASSWORD}');
    ELSE
      EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', '${KONGA_DB_USER}', '${KONGA_DB_PASSWORD}');
    END IF;
  END
  \$\$;

  SELECT format('CREATE DATABASE konga OWNER %I', '${KONGA_DB_USER}')
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'konga')\gexec
SQL
