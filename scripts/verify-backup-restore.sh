#!/usr/bin/env sh
set -eu

# Performs a non-destructive restore drill into a generated temporary PostgreSQL database, then
# removes that temporary database. It never writes to the configured application database.
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  printf 'Usage: %s path/to/job-platform-YYYYMMDDTHHMMSSZ.sql.gz\n' "$0" >&2
  exit 64
fi

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.full.yml}"
BACKUP_FILE="$1"
VERIFY_DATABASE="${VERIFY_DATABASE:-job_platform_restore_verify_$(date -u +%Y%m%d%H%M%S)}"

case "$VERIFY_DATABASE" in
  *[!a-z0-9_]* | '') printf 'VERIFY_DATABASE must contain only lowercase letters, numbers, and underscores.\n' >&2; exit 65 ;;
esac

CREATED=0
cleanup() {
  if [ "$CREATED" -eq 1 ]; then
    docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d postgres -c 'drop database if exists $VERIFY_DATABASE'" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d postgres -c 'create database $VERIFY_DATABASE'"
CREATED=1
gzip -cd "$BACKUP_FILE" | docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $VERIFY_DATABASE"
VERSION="$(docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $VERIFY_DATABASE -tAc 'select max(version) from flyway_schema_history where success'")"
TABLES="$(docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c "psql -v ON_ERROR_STOP=1 -U \"\$POSTGRES_USER\" -d $VERIFY_DATABASE -tAc \"select count(*) from information_schema.tables where table_schema='public' and table_name in ('jobs','job_runs','execution_attempts','file_assets')\"")"

if [ "$VERSION" != "3" ] || [ "$TABLES" != "4" ]; then
  printf 'Restore drill failed: expected schema v3 and core tables, found schema=%s tables=%s\n' "$VERSION" "$TABLES" >&2
  exit 1
fi
printf 'Restore drill passed: %s restored into temporary database %s and verified.\n' "$BACKUP_FILE" "$VERIFY_DATABASE"
