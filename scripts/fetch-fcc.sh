#!/usr/bin/env bash
# Fetch FCC "Consumer Complaints Data - Unwanted Calls" rows with a caller
# number, newer than the aging window, as CSV (Socrata export, paginated).
# The public view returns no per-row dates, so the date filter lives here
# (server-side) and the pipeline stamps rows with --today.
# Usage: scripts/fetch-fcc.sh <out-dir> [max-age-days=180]
set -euo pipefail

OUT_DIR="${1:?usage: fetch-fcc.sh <out-dir> [max-age-days]}"
MAX_AGE_DAYS="${2:-180}"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
BASE="https://opendata.fcc.gov/resource/vakf-fz8e.csv"
CUTOFF="$(date -u -d "-${MAX_AGE_DAYS} day" +%FT00:00:00.000)"
WHERE="caller_id_number!='' AND issue_date > '${CUTOFF}'"
PAGE=50000

mkdir -p "$OUT_DIR"
offset=0
page=0
while :; do
  f="$OUT_DIR/fcc_unwanted_calls_$page.csv"
  curl -sfG -A "$UA" "$BASE" \
    --data-urlencode "\$select=caller_id_number,issue,type_of_call_or_messge" \
    --data-urlencode "\$where=$WHERE" \
    --data-urlencode "\$limit=$PAGE" \
    --data-urlencode "\$offset=$offset" \
    -o "$f"
  rows=$(($(wc -l <"$f") - 1))
  if [ "$rows" -le 0 ]; then
    rm -f "$f"
    break
  fi
  echo "fetched page $page ($rows rows)"
  [ "$rows" -lt "$PAGE" ] && break
  offset=$((offset + PAGE))
  page=$((page + 1))
done

total=$(cat "$OUT_DIR"/fcc_unwanted_calls_*.csv 2>/dev/null | grep -c . || echo 0)
[ "$total" -gt 1 ] || { echo "error: no FCC rows fetched" >&2; exit 1; }
echo "FCC rows (incl. headers): $total in $OUT_DIR"
