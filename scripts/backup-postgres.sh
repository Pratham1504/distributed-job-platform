#!/usr/bin/env sh
set -eu

# Creates a portable logical backup from the Compose PostgreSQL service. The output directory is
# intentionally ignored by Git; copy the encrypted file to approved off-host storage afterwards.
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.full.yml}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DESTINATION="$BACKUP_DIR/job-platform-$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"
docker-compose -f "$COMPOSE_FILE" exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > "$DESTINATION"
printf 'PostgreSQL backup created: %s\n' "$DESTINATION"
