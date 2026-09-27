#!/usr/bin/env sh
set -eu

# Restores a backup into the running Compose PostgreSQL service. It refuses a non-empty database
# so an operator must first provision an empty target as required by the recovery runbook.
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  printf 'Usage: %s path/to/job-platform-YYYYMMDDTHHMMSSZ.sql.gz\n' "$0" >&2
  exit 64
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.full.yml}"
BACKUP_FILE="$1"
EXISTS="$(docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select to_regclass('"'"'public.flyway_schema_history'"'"') is not null"')"
if [ "$EXISTS" = "t" ]; then
  printf 'Refusing to restore into a non-empty database. Provision an empty target first.\n' >&2
  exit 65
fi

gzip -cd "$BACKUP_FILE" | docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
printf 'PostgreSQL restore completed from: %s\n' "$BACKUP_FILE"
