#!/usr/bin/env bash
set -euo pipefail
umask 077

backup_dir=/var/backups/dental-rebuild/daily
schema=dental_rebuild_20261026
credentials=/etc/dental-rebuild/backup-mysql.cnf
retention="${DENTAL_BACKUP_RETENTION_DAYS:-14}"
[[ "$retention" =~ ^[1-9][0-9]{0,2}$ ]]
[[ -r "$credentials" ]]
install -d -m 0700 "$backup_dir"
exec 9>"$backup_dir/.lock"
flock -n 9

archive="dental-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
tmp="$(mktemp "$backup_dir/.backup-XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT
mysqldump --defaults-extra-file="$credentials" --single-transaction --quick \
    --no-tablespaces --set-gtid-purged=OFF --routines --triggers "$schema" | gzip -c > "$tmp"
test -s "$tmp"
gzip -t "$tmp"
mv -- "$tmp" "$backup_dir/$archive"
(cd "$backup_dir"; sha256sum -- "$archive" > "$archive.sha256")
find "$backup_dir" -maxdepth 1 -type f -name 'dental-*.sql.gz*' -mtime "+$retention" -delete
