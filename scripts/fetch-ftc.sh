#!/usr/bin/env bash
# Fetch the last N days of FTC DNC complaint CSVs (public data).
# Usage: scripts/fetch-ftc.sh <out-dir> [days=7]
# The FTC serves 403 to non-browser user agents; a UA header is required.
set -euo pipefail

OUT_DIR="${1:?usage: fetch-ftc.sh <out-dir> [days]}"
DAYS="${2:-7}"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
BASE="https://www.ftc.gov/sites/default/files"

mkdir -p "$OUT_DIR"
fetched=0
for i in $(seq 1 "$DAYS"); do
  day="$(date -u -d "-$i day" +%F)"
  f="DNC_Complaint_Numbers_${day}.csv"
  # Not every calendar day has a file (weekends/holidays) — skip 404s.
  code="$(curl -sL -A "$UA" -o "$OUT_DIR/$f" -w '%{http_code}' "$BASE/$f")"
  if [ "$code" = "200" ] && head -1 "$OUT_DIR/$f" | grep -q "Company_Phone_Number"; then
    echo "fetched $f ($(wc -l <"$OUT_DIR/$f") rows)"
    fetched=$((fetched + 1))
  else
    rm -f "$OUT_DIR/$f"
  fi
done

if [ "$fetched" -eq 0 ]; then
  echo "error: no files fetched" >&2
  exit 1
fi
echo "$fetched file(s) in $OUT_DIR"
