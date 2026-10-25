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
case "$(basename "$state_archive")" in
 "sifangguan-ota-state-daily-${snapshot}.tar.gz.enc") state_format=STANDALONE_TAR_V1 ;;
 "sifangguan-ota-state-daily-${snapshot}.snapshot.json.enc") state_format=SNAPSHOT_V2 ;;
 *) echo 'WRONG_STATE_SNAPSHOT' >&2;exit 1 ;;
esac
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
snapshot_tool=/usr/local/lib/hotel-ai-os/core-state-snapshots.py
# Exclude backup/GC while reading this complete immutable closure.
exec 9>/run/lock/hotel-ai-os-backup.lock
flock -x 9
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
install -d -m 0700 "$work/state"
if [[ "$state_format" == SNAPSHOT_V2 ]]; then
 "$snapshot_tool" restore --state-root /var/backups/sifangguan-ota/state \
   --index "$state_archive" --database "$db_archive" --snapshot "$snapshot" \
   --key "$key" --destination "$work/state" >"$work/state-proof.json"
else
 decrypt <"$state_archive" >"$work/state.tar.gz"
 "$tool" extract "$work/state.tar.gz" "$work/state" >/dev/null
fi
test "$(sudo -u postgres psql -XAt --dbname postgres --command "select count(*) from pg_database where datname='$restore_db'")" = 0
sudo -u postgres createdb --owner hotel_ai_os_owner "$restore_db"
created=true
decrypt <"$db_archive" | sudo -u postgres pg_restore --exit-on-error --single-transaction --dbname "$restore_db"
sudo -u postgres psql -XAtq --dbname "$restore_db" <"$reference_sql" >"$work/restored-references.json"
"$tool" verify "$work/state/var/lib/hotel-ai-os/attachments" "$work/state/var/lib/hotel-ai-os/backup-core-originals-manifest.json" "$snapshot" "$work/restored-references.json" >"$work/proof.json"
python3 - "$work/proof.json" "$state_format" "$work/state-proof.json" <<'PY'
import json,sys
from pathlib import Path
path=Path(sys.argv[1]);proof=json.loads(path.read_text());proof['stateFormat']=sys.argv[2]
if sys.argv[2]=='SNAPSHOT_V2': proof['stateRestore']=json.loads(Path(sys.argv[3]).read_text())
path.write_text(json.dumps(proof,sort_keys=True)+'\n')
PY
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
if [[ "$state_format" == SNAPSHOT_V2 ]]; then
 python3 - "$work/state-proof.json" <<'PY' >>"$proof_dir/${snapshot}.archives.sha256"
import json,sys
for digest in sorted(json.load(open(sys.argv[1]))['packs']):
 print(digest+'  /var/backups/sifangguan-ota/state/objects/'+digest+'.pack.enc')
PY
fi
printf 'PAIRED_DATABASE_ORIGINALS_RESTORE_OK snapshot=%s proof=%s\n' "$snapshot" "$proof_dir/${snapshot}.json"
