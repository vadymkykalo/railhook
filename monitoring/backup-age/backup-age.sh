#!/bin/sh
# Every five minutes, the newest scheduled backup's age and size as node-exporter textfile
# metrics. Reads the dumps the db-backup sidecar writes (webhook_platform_*.dump), so it
# measures what a restore would need — a file — rather than whether a script ran.
set -eu

DIR=/backups
OUT=/textfile/railhook_backup.prom

while :; do
  newest=$(ls -1t "$DIR"/webhook_platform_*.dump 2>/dev/null | head -1 || true)
  count=$(ls -1 "$DIR"/webhook_platform_*.dump 2>/dev/null | wc -l | tr -d ' ')
  {
    echo "# HELP railhook_backup_files Scheduled database dumps in the backup directory."
    echo "# TYPE railhook_backup_files gauge"
    echo "railhook_backup_files ${count}"
    if [ -n "$newest" ]; then
      echo "# HELP railhook_backup_newest_timestamp_seconds Modification time of the newest dump."
      echo "# TYPE railhook_backup_newest_timestamp_seconds gauge"
      echo "railhook_backup_newest_timestamp_seconds $(stat -c %Y "$newest")"
      echo "# HELP railhook_backup_newest_size_bytes Size of the newest dump."
      echo "# TYPE railhook_backup_newest_size_bytes gauge"
      echo "railhook_backup_newest_size_bytes $(stat -c %s "$newest")"
    fi
  } > "$OUT.tmp"
  mv "$OUT.tmp" "$OUT"
  sleep 300
done
