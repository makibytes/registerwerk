#!/usr/bin/env bash
set -euo pipefail

# Flags NEW Flyway migrations for unguarded destructive DDL — DROP TABLE, DROP COLUMN, TRUNCATE,
# and an ALTER TABLE ... DROP ... — so a migration that would silently destroy data can't merge
# without a human explicitly saying it's intentional. This repo's own convention for anything that
# looks like "remove data" is DETACH-then-archive, never an unconditional DROP (see
# rw_retire_partitions in the baseline schema) — a real DROP should be the rare,
# reviewed exception, not something that slips through unnoticed in a large diff.
#
# Only NEW migration files (added since the base ref) are scanned. This repo never edits an
# already-applied migration (see CLAUDE.md: "never edit existing"), so there is no reason to
# re-flag history — and re-scanning it would make this check fail forever on migrations that
# already shipped and were already reviewed.
#
# Acknowledge a statement (or a consecutive block of them — the ack stays in effect until the
# next line that isn't blank, a comment, or itself a flagged statement) with a comment line
# directly above it:
#   -- migration-safety: ack (<why this is safe>)
#
# Usage: scripts/check-destructive-migrations.sh [base-ref]   (default: origin/main)

BASE_REF="${1:-origin/main}"
MIGRATION_DIR="backend/src/main/resources/db/migration"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Case-insensitive; deliberately does NOT try to catch DELETE/UPDATE without a WHERE clause —
# those can legitimately span multiple lines before the terminating `;`, and this repo's actual
# data-removal migrations go through the app-level retention jobs (RetentionSweepJob), not raw
# DML in a migration, so the realistic risk here is schema-destroying DDL, not row-level DML.
PATTERN='DROP[[:space:]]+TABLE|DROP[[:space:]]+COLUMN|TRUNCATE|ALTER[[:space:]]+TABLE[^;]*DROP'
ACK_PATTERN='--[[:space:]]*migration-safety:[[:space:]]*ack'

# `mapfile`/`readarray` needs bash 4+ (not present in macOS's stock /bin/bash, 3.2) — a plain
# while-read loop into an array works identically back to bash 3.
NEW_FILES=()
while IFS= read -r NEW_FILE; do
  [ -n "$NEW_FILE" ] && NEW_FILES+=("$NEW_FILE")
done < <(git diff --name-only --diff-filter=A "${BASE_REF}...HEAD" -- "$MIGRATION_DIR" 2>/dev/null || true)

if [ "${#NEW_FILES[@]}" -eq 0 ]; then
  echo "No new migration files since ${BASE_REF}."
  exit 0
fi

FAILED=0
for FILE in "${NEW_FILES[@]}"; do
  [ -f "$FILE" ] || continue
  echo "Checking $FILE..."
  ACKED=0
  LINE_NO=0
  while IFS= read -r LINE; do
    LINE_NO=$((LINE_NO + 1))
    TRIMMED="$(echo "$LINE" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"

    if echo "$TRIMMED" | grep -qiE -- "$ACK_PATTERN"; then
      ACKED=1
      continue
    fi
    if [ -z "$TRIMMED" ]; then
      continue
    fi
    # A plain comment line (e.g. a wrapped multi-line ack explanation) neither resets nor sets
    # the ack by itself — only the line actually containing the ack marker does that.
    if echo "$TRIMMED" | grep -qE '^--'; then
      continue
    fi
    if echo "$LINE" | grep -qiE "$PATTERN"; then
      if [ "$ACKED" -eq 1 ]; then
        echo "  ok (acknowledged) line ${LINE_NO}: ${TRIMMED}"
      else
        echo "  UNACKNOWLEDGED DESTRUCTIVE STATEMENT at ${FILE}:${LINE_NO}"
        echo "    ${TRIMMED}"
        echo "    Add '-- migration-safety: ack (<why this is safe>)' on the line above to confirm this is intentional."
        FAILED=1
      fi
      continue
    fi
    # Any other real, non-comment content resets the ack — it only covers the block directly
    # under it (plus any comment lines in between).
    ACKED=0
  done < "$FILE"
done

if [ "$FAILED" -eq 1 ]; then
  echo
  echo "One or more new migrations contain unacknowledged destructive statements."
  exit 1
fi

echo "All new migrations passed the destructive-DDL check."
