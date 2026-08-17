#!/usr/bin/env python3
"""Projects storage growth for the append-only, high-volume tables this registry never stops
writing to, from REAL measurements of whatever database it's pointed at — not a guess.

Two numbers drive the projection, both queried live:
  1. Current total size (pg_partition_tree-aware, so a partitioned table's child partitions are
     summed correctly instead of reporting the empty parent's 0 bytes) and row count.
  2. An empirical bytes/day and rows/day rate, derived from the actual observed timestamp range
     of the data already in the table (oldest row's timestamp to now) — not an assumed constant.

Usage:
    scripts/project-db-growth.py                                   # via `docker compose exec postgres`
    scripts/project-db-growth.py --psql-cmd "psql -h db.example.com -U registerwerk -d registerwerk"
    scripts/project-db-growth.py --horizon-days 30 90 365 730

HONESTY NOTE: this tool is real and the query methodology is sound, but its OUTPUT is only as
meaningful as the database you point it at. Run against this repo's own demo/seed stack, the
"empirical daily rate" is computed from a handful of rows seeded once, over a window of days to
weeks — not real sustained production traffic — so the projected figures below are a
methodology demonstration, not a real capacity plan. Re-run this against a database that has
actually been receiving real traffic for a meaningful period before trusting the numbers for
retention-window or storage-budget decisions.
"""

import argparse
import subprocess
import sys
from dataclasses import dataclass

# (table, timestamp column used to derive the observed date range, whether RetentionSweepJob /
# PartitionMaintenanceJob currently bounds this table's growth at all — see
# backend/src/main/java/de/makibytes/registerwerk/infrastructure/{RetentionSweepJob,
# PartitionMaintenanceJob}.java for what's actually wired up as of this script's writing).
TABLES = [
    ("token_transfer", "occurred_at", "partitioned + archived via rw_retire_partitions (DETACH only, legal-hold aware)"),
    ("blockchain_transaction", "created_at", "partitioned + archived via rw_retire_partitions (DETACH only, legal-hold aware)"),
    ("audit_event", "occurred_at", "partitioned, archive-only (10yr DE/LI WORM window) — never dropped by policy"),
    ("webhook_delivery", "created_at", "RetentionSweepJob (batched delete)"),
    ("login_attempt", "updated_at", "RetentionSweepJob (batched delete)"),
    ("event_publication", "completion_date", "RetentionSweepJob (batched delete, Modulith outbox)"),
    ("screening_run", "started_at", "NOT swept — archived per GwG evidence retention, no automatic delete"),
    ("chain_drift_event", "detected_at", "RetentionSweepJob sweeps CLOSED rows only"),
]


@dataclass
class TableStats:
    name: str
    size_bytes: int
    row_count: int
    oldest_ts_days_ago: float | None
    bytes_per_day: float
    rows_per_day: float


def run_sql(psql_cmd: list[str], sql: str) -> str:
    result = subprocess.run(
        psql_cmd + ["-tAc", sql],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        print(f"  query failed: {result.stderr.strip()}", file=sys.stderr)
        return ""
    return result.stdout.strip()


def measure(psql_cmd: list[str], table: str, ts_column: str) -> TableStats | None:
    # pg_partition_tree() returns rows ONLY for a table that's actually part of partition
    # infrastructure (a partitioned table or one of its partitions) — confirmed by hand against
    # this repo's own event_publication (a plain, non-partitioned table): it came back with ZERO
    # rows, not "itself as a single row" as the docs' phrasing suggests. COALESCE'ing the SUM
    # papered over that as a silent (wrong) 0 bytes rather than surfacing it, so this now falls
    # back to a direct pg_total_relation_size() when the partition-tree query is empty.
    size_row = run_sql(psql_cmd, f"""
        SELECT COALESCE(SUM(pg_total_relation_size(pt.relid)), 0)
        FROM pg_partition_tree('{table}'::regclass) pt
    """)
    if not size_row:
        return None
    size_bytes = int(size_row)
    if size_bytes == 0:
        direct = run_sql(psql_cmd, f"SELECT pg_total_relation_size('{table}'::regclass)")
        if direct:
            size_bytes = int(direct)

    count_row = run_sql(psql_cmd, f'SELECT COUNT(*) FROM "{table}"')
    row_count = int(count_row) if count_row else 0

    oldest_days_ago = None
    bytes_per_day = 0.0
    rows_per_day = 0.0
    if row_count > 0:
        span_row = run_sql(psql_cmd, f"""
            SELECT EXTRACT(EPOCH FROM (now() - MIN("{ts_column}"))) / 86400.0
            FROM "{table}"
        """)
        if span_row and span_row.lower() != "":
            try:
                oldest_days_ago = max(float(span_row), 1.0)  # floor at 1 day to avoid a divide spike
                bytes_per_day = size_bytes / oldest_days_ago
                rows_per_day = row_count / oldest_days_ago
            except ValueError:
                pass

    return TableStats(table, size_bytes, row_count, oldest_days_ago, bytes_per_day, rows_per_day)


def human_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024.0:
            return f"{n:,.1f} {unit}"
        n /= 1024.0
    return f"{n:,.1f} PB"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--psql-cmd", default="docker compose exec -T postgres psql -U registerwerk -d registerwerk",
        help="Command used to run psql, split on spaces (default: the local Compose stack's postgres service)",
    )
    parser.add_argument(
        "--horizon-days", nargs="+", type=int, default=[30, 90, 365, 1095],
        help="Projection horizons in days (default: 30 90 365 1095)",
    )
    args = parser.parse_args()
    psql_cmd = args.psql_cmd.split()

    print("Measuring current table sizes and empirical growth rates (real query, real database)...\n")

    rows = []
    for table, ts_column, retention_note in TABLES:
        stats = measure(psql_cmd, table, ts_column)
        if stats is None:
            print(f"  {table}: could not query (table missing or connection failed) — skipped")
            continue
        rows.append((stats, retention_note))

    if not rows:
        print("No tables could be measured — check --psql-cmd connects correctly.", file=sys.stderr)
        return 1

    header = ["table", "current size", "rows", "rows/day", f"+{args.horizon_days[0]}d"]
    for d in args.horizon_days[1:]:
        header.append(f"+{d}d")
    header.append("retention")
    print(" | ".join(header))
    print("-" * 100)

    for stats, retention_note in rows:
        cells = [
            stats.name,
            human_bytes(stats.size_bytes),
            f"{stats.row_count:,}",
            f"{stats.rows_per_day:,.1f}",
        ]
        for d in args.horizon_days:
            projected = stats.size_bytes + stats.bytes_per_day * d
            cells.append(human_bytes(projected))
        cells.append(retention_note)
        print(" | ".join(cells))

    print(
        "\nNOTE: growth rates above are derived from each table's OWN oldest-row timestamp to now —"
        " a table seeded once, recently, with a handful of demo rows will show a misleadingly high"
        " (or low) daily rate. This is a real measurement of whatever database --psql-cmd points at,"
        " not a synthetic guess, but see this script's own module docstring before trusting these"
        " figures for a real retention-window decision."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
