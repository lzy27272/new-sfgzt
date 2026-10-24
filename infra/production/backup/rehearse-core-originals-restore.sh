#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$#" -eq 3 || { echo 'Usage: rehearse-core-originals-restore.sh DB_DAILY.enc STATE_DAILY.enc SNAPSHOT_ID' >&2;exit 2; }
db_archive=$1
state_archive=$2
snapshot=$3
[[ "$snapshot" =~ ^[0-9]{8}T[0-9]{6}[+-][0-9]{4}$ ]]
test "$(basename "$db_archive")" = "hotel_ai_os-daily-${snapshot}.dump.enc"
test "$(basename "$state_archive")" = "sifangguan-ota-state-daily-${snapshot}.tar.gz.enc"
test "$(dirname "$(realpath -e "$db_archive")")" = /var/backups/hotel-ai-os/postgres/daily
test "$(dirname "$(realpath -e "$state_archive")")" = /var/backups/sifangguan-ota/state/daily
for archive in "$db_archive" "$state_archive"; do
 test -f "$archive" && test ! -L "$archive"
 test "$(stat -c '%U:%G' "$archive")" = root:root
 (cd "$(dirname "$archive")" && sha256sum --check "$(basename "$archive").sha256")
done
tool=/usr/local/lib/hotel-ai-os/core-originals-manifest.py
reference_sql=/usr/local/lib/hotel-ai-os/core-originals-reference.sql
key=/etc/hotel-ai-os/backup-encryption.key
test "$(stat -c '%U:%a' "$key")" = root:600
work="$(mktemp -d /var/backups/hotel-ai-os/postgres/.restore-originals.XXXXXXXX)"
restore_db="hotel_ai_os_originals_restore_$(date +%s)_$$"
[[ "$restore_db" =~ ^hotel_ai_os_originals_restore_[0-9]+_[0-9]+$ ]]
created=false
cleanup(){
 local result=$?
 trap - EXIT INT TERM
 if test "$created" = true; then
  sudo -u postgres psql -Xq --dbname postgres --command "select pg_terminate_backend(pid) from pg_stat_activity where datname='$restore_db' and pid<>pg_backend_pid()" >/dev/null || result=1
  sudo -u postgres dropdb "$restore_db" || result=1
 fi
 if [[ "$(realpath -e "$work")" == /var/backups/hotel-ai-os/postgres/.restore-originals.* ]] && test ! -L "$work"; then
  find "$work" -xdev -depth -delete || result=1
 else result=1;fi
 exit "$result"
}
trap cleanup EXIT INT TERM
decrypt(){ openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$key"; }
decrypt <"$state_archive" >"$work/state.tar.gz"
install -d -m 0700 "$work/state"
"$tool" extract "$work/state.tar.gz" "$work/state" >/dev/null
test "$(sudo -u postgres psql -XAt --dbname postgres --command "select count(*) from pg_database where datname='$restore_db'")" = 0
sudo -u postgres createdb --owner hotel_ai_os_owner "$restore_db"
created=true
decrypt <"$db_archive" | sudo -u postgres pg_restore --exit-on-error --single-transaction --dbname "$restore_db"
sudo -u postgres psql -XAtq --dbname "$restore_db" <"$reference_sql" >"$work/restored-references.json"
"$tool" verify "$work/state/var/lib/hotel-ai-os/attachments" "$work/state/var/lib/hotel-ai-os/backup-core-originals-manifest.json" "$snapshot" "$work/restored-references.json" >"$work/proof.json"
sudo -u postgres psql -XAt --set ON_ERROR_STOP=1 --dbname "$restore_db" --command "select 'flyway|'||coalesce(max(version::int),0)||'|'||count(*) filter(where not success) from flyway_schema_history;select 'forced_rls|'||count(*) from pg_class where relnamespace='public'::regnamespace and relforcerowsecurity" >"$work/database-proof.txt"
grep -Eq '^flyway\|[0-9]+\|0$' "$work/database-proof.txt"
grep -Eq '^forced_rls\|[1-9][0-9]*$' "$work/database-proof.txt"
original_rls="$(sudo -u postgres psql -XAt --set ON_ERROR_STOP=1 --dbname "$restore_db" --command "select count(*)||'|'||count(*) filter(where not(relrowsecurity and relforcerowsecurity)) from pg_class where relnamespace='public'::regnamespace and relname in ('attachment','task_evidence','daily_report_evidence','procurement_attachment','daily_activity_photo','ai_evidence_sample','ai_evidence_photo','academy_course','finance_attachment','fo_attachment','fb_attachment','fs_asset_gain_original','finance_invoice_intake')")"
[[ "$original_rls" =~ ^[1-9][0-9]*\|0$ ]]
printf 'original_tables_rls|%s\n' "$original_rls" >>"$work/database-proof.txt"
proof_dir=/var/backups/hotel-ai-os/postgres/originals-restore-proofs
install -d -o root -g root -m 0700 "$proof_dir"
test ! -e "$proof_dir/${snapshot}.json"
install -o root -g root -m 0600 "$work/proof.json" "$proof_dir/${snapshot}.json"
install -o root -g root -m 0600 "$work/database-proof.txt" "$proof_dir/${snapshot}.database.txt"
sha256sum "$db_archive" "$state_archive" >"$proof_dir/${snapshot}.archives.sha256"
printf 'PAIRED_DATABASE_ORIGINALS_RESTORE_OK snapshot=%s proof=%s\n' "$snapshot" "$proof_dir/${snapshot}.json"
