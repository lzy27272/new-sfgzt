#!/bin/bash
set -Eeuo pipefail
shopt -s inherit_errexit
umask 077
export PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
unset BASH_ENV ENV PYTHONPATH PYTHONHOME
readonly INSTALLED='/usr/local/sbin/hotel-ai-os-install-full-ci-release'
readonly LIBRARY='/usr/local/lib/hotel-ai-os/bootstrap'
readonly VERIFIER="${LIBRARY}/full-ci-release.py"
readonly COORDINATOR='/usr/local/sbin/hotel-ai-os-install-fast-lane-release'
readonly OCR='/usr/local/sbin/hotel-ai-os-finance-ocr-release'
readonly STAGE='/tmp/hotel-ai-os-release'
readonly INCOMING='/tmp/hotel-ai-os-full-ci-incoming'
readonly EVIDENCE='/var/lib/hotel-ai-os-deploy/full-ci'
readonly RUNTIME='/run/hotel-ai-os-full-ci'
readonly BACKUP='/usr/local/lib/hotel-ai-os/backup-postgres.sh'
readonly RESTORE='/usr/local/lib/hotel-ai-os/rehearse-core-originals-restore.sh'
readonly REFERENCES='/usr/local/lib/hotel-ai-os/core-originals-reference.sql'
readonly CORE='hotel-ai-os-core-api.service'
readonly KEY='/etc/hotel-ai-os/backup-encryption.key'
release_id='' source_commit='' previous_backend='' previous_web='' proof='' clone='' runtime=''
begun=false switched=false migration_started=false audited=false ocr_prepared=false ocr_activation_started=false core_stop_started=false clone_created=false

fail(){ printf 'FULL_CI_RELEASE_ERROR %s\n' "$*" >&2; return 1; }
root_file(){ test -f "$1" && test ! -L "$1" && test "$(stat -c '%u:%g:%h' -- "$1")" = '0:0:1' && test -z "$(find "$1" -maxdepth 0 -perm /022 -print)" || fail "Unsafe installed file: $1"; }
valid_id(){ [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$1" != *'..'* ]]; }
valid_commit(){ [[ "$1" =~ ^[a-f0-9]{40}$ ]]; }
valid_hash(){ [[ "$1" =~ ^[a-f0-9]{64}$ ]]; }
coordinator(){ "$COORDINATOR" "$@"; }
ocr(){ "$OCR" "$@"; }
current_target(){
  local link=$1 prefix=$2 result
  test -L "$link"; result="$(readlink -f -- "$link")"
  [[ "$result" == "$prefix/"* ]]; valid_id "${result##*/}"
  test "$result" = "$prefix/${result##*/}" && test -d "$result" && test ! -L "$result"
  test "$(stat -c '%u:%g' "$result")" = '0:0'
  printf '%s\n' "$result"
}
policy(){
  local self_sha verifier_sha flyway_sha installer_sha audit_sha
  for path in "$INSTALLED" "$VERIFIER" "$LIBRARY/FullCiFlyway.java" "$LIBRARY/60-install-release.sh" "$LIBRARY/100-final-audit.sh"; do root_file "$path"; done
  self_sha="$(sha256sum "$INSTALLED" | cut -d' ' -f1)"; verifier_sha="$(sha256sum "$VERIFIER" | cut -d' ' -f1)"
  flyway_sha="$(sha256sum "$LIBRARY/FullCiFlyway.java" | cut -d' ' -f1)"; installer_sha="$(sha256sum "$LIBRARY/60-install-release.sh" | cut -d' ' -f1)"; audit_sha="$(sha256sum "$LIBRARY/100-final-audit.sh" | cut -d' ' -f1)"
  printf 'FULL_CI_POLICY version=1 sha256=%s verifier=%s flyway=%s installer=%s audit=%s\n' "$self_sha" "$verifier_sha" "$flyway_sha" "$installer_sha" "$audit_sha"
}
health(){
  local response
  for _attempt in $(seq 1 60); do
    if response="$(curl --fail --silent --show-error --noproxy '*' --max-time 3 http://127.0.0.1:18080/actuator/health)" && python3 -I -c 'import json,sys; assert json.load(sys.stdin)["status"]=="UP"' <<<"$response"; then return 0; fi
    sleep 2
  done
  fail 'Core health did not become UP'
}
drop_clone(){
  if test "$clone_created" = true; then
    [[ "$clone" =~ ^hotel_ai_os_full_ci_[0-9]+_[0-9]+$ ]] || return 1
    sudo -u postgres psql -Xq --set ON_ERROR_STOP=1 --dbname postgres --command "select pg_terminate_backend(pid) from pg_stat_activity where datname='$clone' and pid<>pg_backend_pid()" >/dev/null || return 1
    sudo -u postgres dropdb "$clone" || return 1
    clone_created=false
  fi
}
archive_stage(){
  if test -d "$STAGE" && test ! -L "$STAGE"; then
    test "$(stat -c '%u:%g' "$STAGE")" = '0:0'
    test ! -e "$proof/stage" && test ! -L "$proof/stage"
    mv -T -- "$STAGE" "$proof/stage"
  fi
}
rollback(){
  test "$begun" = true || return 0
  if test "$audited" = true; then
    printf '%s\n' 'FULL_CI_VERIFIED_RELEASE_ACTIVE_OCR_COMMIT_CLEANUP_REQUIRES_REVIEW' >&2
    return 1
  fi
  local failed=0
  if test "$core_stop_started" = true; then systemctl stop "$CORE" || failed=1; fi
  # PREPARE never changes active configuration; the OCR helper deliberately only rolls back an activation.
  if test "$ocr_activation_started" = true; then ocr ROLLBACK "$release_id" "$source_commit" || failed=1; fi
  if test "$switched" = true; then
    test -d "$previous_backend" && test -d "$previous_web" || return 1
    ln -sfn -- "$previous_backend" /opt/hotel-ai-os/current || failed=1
    ln -sfn -- "$previous_web" /srv/www/hotel-ai-os/current || failed=1
  fi
  if test "$migration_started" = true; then
    # No destructive inverse migration and no speculative old-code restart after any live DDL attempt.
    printf '%s\n' 'FULL_CI_MAINTENANCE_REQUIRED database retained; prior Core/Web/OCR configuration restoration attempted, Core remains stopped and FULL marker retained' >&2
    return 1
  fi
  if test "$failed" -eq 0; then
    if test "$core_stop_started" = true; then systemctl start "$CORE" || failed=1; fi
    if test "$failed" -eq 0; then health && coordinator ABORT_FULL "$source_commit" "$release_id" || failed=1; fi
  fi
  return "$failed"
}
cleanup(){
  local result=$?
  trap - EXIT INT TERM
  drop_clone || result=1
  if test "$result" -ne 0; then rollback || true; fi
  if test -n "$proof"; then archive_stage || result=1; fi
  if test "$result" -ne 0; then printf 'FULL_CI_RELEASE_FAILED releaseId=%s evidence=%s\n' "$release_id" "$proof" >&2; fi
  exit "$result"
}
hold_backup_lock(){
  exec 9>/run/lock/hotel-ai-os-backup.lock
  flock -n 9 || fail 'Another backup holds the selected snapshot staging lock'
}
release_backup_lock(){ flock -u 9; exec 9>&-; }
check_restore_capacity(){ python3 -I "$VERIFIER" restore-capacity --snapshot "$2" --backup-log "$proof/backup.log" >"$proof/restore-capacity-$1.json"; }
backup_restore_and_rehearse(){
  "$BACKUP" >"$proof/backup.log" 2>&1
  grep -Fxq COMPLETE_BACKUP_EXPORT_OK "$proof/backup.log"
  hold_backup_lock || return 1
  local snapshot db state before after
  snapshot="$(sed -n 's/^pairedOriginalsSnapshot=\([^ ]*\) type=CORE_AND_OTA_STATE_WITH_ORIGINALS$/\1/p' "$proof/backup.log")"
  [[ "$snapshot" =~ ^[0-9]{8}T[0-9]{6}[+-][0-9]{4}$ ]]
  db="/var/backups/hotel-ai-os/postgres/daily/hotel_ai_os-daily-${snapshot}.dump.enc"
  state="/var/backups/sifangguan-ota/state/daily/sifangguan-ota-state-daily-${snapshot}.snapshot.json.enc"
  if test ! -f "$state"; then state="/var/backups/sifangguan-ota/state/daily/sifangguan-ota-state-daily-${snapshot}.tar.gz.enc"; fi
  check_restore_capacity originals "$snapshot" || return 1
  "$RESTORE" "$db" "$state" "$snapshot" >"$proof/paired-originals-restore.log" 2>&1
  grep -Fq "PAIRED_DATABASE_ORIGINALS_RESTORE_OK snapshot=$snapshot " "$proof/paired-originals-restore.log"
  check_restore_capacity migration "$snapshot" || return 1
  clone="hotel_ai_os_full_ci_$(date +%s)_$$"
  test "$(sudo -u postgres psql -XAt --set ON_ERROR_STOP=1 --dbname postgres --command "select count(*) from pg_database where datname='$clone'")" = 0
  sudo -u postgres createdb --owner hotel_ai_os_owner "$clone"; clone_created=true
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$KEY" <"$db" | sudo -u postgres pg_restore --exit-on-error --single-transaction --dbname "$clone" >"$proof/clone-restore.log" 2>&1
  before="$(sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname "$clone" --command "select coalesce(max(version::integer),0)||'|'||count(*) filter(where not success) from flyway_schema_history")"
  [[ "$before" =~ ^[0-9]+\|0$ ]]
  sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname "$clone" <"$REFERENCES" >"$proof/references-before.json"
  python3 -I "$VERIFIER" flyway "$runtime" "$clone" MIGRATE >"$proof/clone-migration.log" 2>&1
  grep -Fxq FULL_CI_FLYWAY_MIGRATE_PASS "$proof/clone-migration.log"
  after="$(sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname "$clone" --command "select max(version::integer)||'|'||count(*) filter(where not success) from flyway_schema_history")"
  test "$after" = "$migration_version|0"
  sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname "$clone" <"$REFERENCES" >"$proof/references-after.json"
  cmp --silent "$proof/references-before.json" "$proof/references-after.json"
  printf 'FULL_CI_CLONE_MIGRATION_PASS before=%s after=%s originals=UNCHANGED\n' "$before" "$after" >"$proof/clone-result.txt"
  drop_clone
  release_backup_lock
}
install_candidate(){ "$LIBRARY/60-install-release.sh" "$release_id" "$source_commit" "$jar_sha" "$runner_sha" "$index_sha" "$web_tree_sha"; }
migrate_live(){
  python3 -I "$VERIFIER" flyway "$runtime" hotel_ai_os MIGRATE >"$proof/live-migration.log" 2>&1
  grep -Fxq FULL_CI_FLYWAY_MIGRATE_PASS "$proof/live-migration.log"
  test "$(sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname hotel_ai_os --command "select max(version::integer)||'|'||count(*) filter(where not success) from flyway_schema_history")" = "$migration_version|0"
}
final_audit(){ "$LIBRARY/100-final-audit.sh"; }
perform_full_release(){
  coordinator BEGIN_FULL "$source_commit" "$release_id" >"$proof/begin.log" 2>&1; begun=true
  # Marker precedes the long backup/rehearsal, so a fast lane cannot insert a different baseline.
  test "$(cat /var/lib/hotel-ai-os-deploy/deployed-source-commit)" = "$expected_source_commit"
  backup_restore_and_rehearse
  ocr PREPARE "$release_id" "$source_commit" >"$proof/ocr-prepare.log" 2>&1; ocr_prepared=true
  core_stop_started=true
  systemctl stop "$CORE"
  # 60 validates the existing marker through RESUME_FULL; every copied artifact is independently re-hashed.
  switched=true
  install_candidate >"$proof/install.log" 2>&1
  ocr_activation_started=true
  ocr ACTIVATE "$release_id" "$source_commit" >"$proof/ocr-activate.log" 2>&1
  migration_started=true
  migrate_live
  systemctl start "$CORE"; health >"$proof/health.log" 2>&1
  archive_stage
  final_audit >"$proof/final-audit.log" 2>&1
  grep -Fxq FINAL_SERVER_AUDIT_OK "$proof/final-audit.log"; audited=true
  ocr COMMIT "$release_id" "$source_commit" >"$proof/ocr-commit.log" 2>&1
  printf 'FULL_CI_RELEASE_COMPLETE sourceCommit=%s releaseId=%s evidence=%s\n' "$source_commit" "$release_id" "$proof"
}
main(){
  test "$(id -u)" -eq 0 || fail 'Fixed FULL command requires root'
  test "$(readlink -f "${BASH_SOURCE[0]}")" = "$INSTALLED" || fail 'Only the preinstalled root-owned FULL entry may run'
  root_file "$INSTALLED"
  if test "$#" -eq 1 && test "$1" = POLICY; then policy; return; fi
  test "$#" -eq 9 && test "$1" = DEPLOY || fail 'Usage: fixed-command DEPLOY releaseId incoming expectedCommit targetCommit artifactSha classifierSha ciRun ciAttempt'
  release_id=$2; local incoming=$3; expected_source_commit=$4; source_commit=$5; local digest=$6 classifier=$7 ci_run=$8 ci_attempt=$9
  valid_id "$release_id" && valid_commit "$expected_source_commit" && valid_commit "$source_commit" && valid_hash "$digest" && valid_hash "$classifier"
  [[ "$ci_run" =~ ^[1-9][0-9]*$ && "$ci_attempt" =~ ^[1-9][0-9]*$ ]]
  test "$incoming" = "$INCOMING/$release_id"
  # Child fixed root commands must not confuse this explicitly authorized FULL wrapper with their narrow fast-lane caller.
  unset SUDO_USER SUDO_UID SUDO_GID SUDO_COMMAND
  exec 8>/run/lock/hotel-ai-os-full-ci.lock; flock -n 8 || fail 'Another FULL CI wrapper is running'
  policy >/dev/null
  for path in "$COORDINATOR" "$OCR" "$BACKUP" "$RESTORE" "$REFERENCES" "$KEY" /etc/hotel-ai-os/migration.env; do root_file "$path"; done
  test "$(stat -c '%a' "$KEY")" = 600 && test "$(stat -c '%a' /etc/hotel-ai-os/migration.env)" = 600
  test -x /usr/bin/javac && test -x /usr/bin/java
  test ! -e "$STAGE" && test ! -L "$STAGE"
  test "$("$COORDINATOR" STATUS)" = "$expected_source_commit"
  previous_backend="$(current_target /opt/hotel-ai-os/current /opt/hotel-ai-os/releases)"
  previous_web="$(current_target /srv/www/hotel-ai-os/current /srv/www/hotel-ai-os/releases)"
  install -d -o root -g root -m 0700 "$EVIDENCE"
  proof="$EVIDENCE/$release_id"; test ! -e "$proof" && test ! -L "$proof"; install -d -o root -g root -m 0700 "$proof"
  trap cleanup EXIT; trap 'exit 130' INT TERM
  python3 -I "$VERIFIER" snapshot --incoming "$incoming" --destination "$STAGE" --digest "$digest" --commit "$source_commit" --classifier "$classifier" >"$proof/verified-manifest.json"
  printf 'source=%s expected=%s artifactSha=%s ciRun=%s ciAttempt=%s\n' "$source_commit" "$expected_source_commit" "$digest" "$ci_run" "$ci_attempt" >"$proof/approval-identity.txt"
  IFS=$'\t' read -r jar_sha runner_sha index_sha web_tree_sha migration_version < <(python3 -I - "$proof/verified-manifest.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]));a={x['name']:x for x in m['artifacts']}
print('\t'.join([m['core']['jarSha256'],a['migration-runner']['sha256'],m['web']['indexSha256'],m['web']['treeSha256'],m['migration']['version']]))
PY
  )
  valid_hash "$jar_sha" && valid_hash "$runner_sha" && valid_hash "$index_sha" && valid_hash "$web_tree_sha" && [[ "$migration_version" =~ ^[1-9][0-9]*$ ]]
  install -d -o root -g hotelai -m 0710 "$RUNTIME"
  runtime="$RUNTIME/$release_id"; test ! -e "$runtime" && test ! -L "$runtime"
  python3 -I "$VERIFIER" runtime "$STAGE/core-api.jar" "$runtime" >"$proof/runtime.log"
  perform_full_release
}
if [[ "${BASH_SOURCE[0]}" = "$0" ]]; then main "$@"; fi
