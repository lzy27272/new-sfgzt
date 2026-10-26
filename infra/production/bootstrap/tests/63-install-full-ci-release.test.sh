#!/bin/bash
set -Eeuo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
script="${here}/../63-install-full-ci-release.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/full-ci-contract.XXXXXXXX")"
cleanup_fixture(){ case "$fixture" in "${TMPDIR:-/tmp}"/full-ci-contract.*) find "$fixture" -xdev -depth -delete;; *) exit 1;; esac; }
trap cleanup_fixture EXIT
bash -n "$script"
source "$script"
valid_id 'pilot-safe-01'; ! valid_id '../escape'; ! valid_id 'bad id'
valid_commit "$(printf 'a%.0s' {1..40})"; ! valid_commit main
valid_hash "$(printf 'b%.0s' {1..64})"; ! valid_hash 123
proof="$fixture"; release_id='fixture'; source_commit="$(printf 'a%.0s' {1..40})"; expected_source_commit="$(printf 'b%.0s' {1..40})"
events="$fixture/events"
record(){ printf '%s\n' "$*" >>"$events"; }
# Exercise the actual backup/rehearsal function: capacity failure must return before any restore/clone.
(
  function /usr/local/lib/hotel-ai-os/backup-postgres.sh(){ record BACKUP; printf 'COMPLETE_BACKUP_EXPORT_OK\npairedOriginalsSnapshot=20261027T120000+0800 type=CORE_AND_OTA_STATE_WITH_ORIGINALS\n'; }
  function /usr/local/lib/hotel-ai-os/rehearse-core-originals-restore.sh(){ record RESTORE; printf 'PAIRED_DATABASE_ORIGINALS_RESTORE_OK snapshot=20261027T120000+0800 proof=fixture\n'; }
  sudo(){ record FORBIDDEN_CLONE_COMMAND; return 99; }
  hold_backup_lock(){ record LOCK; }
  check_restore_capacity(){ record "CAPACITY $1"; return 1; }
  : >"$events"
  if backup_restore_and_rehearse; then exit 1; fi
  test "$(command cat "$events")" = $'BACKUP\nLOCK\nCAPACITY originals'
  printf 'PASS insufficient restore space stops after successful backup before decrypting or restoring\n'
  check_restore_capacity(){ record "CAPACITY $1"; test "$1" = originals; }
  : >"$events"
  if backup_restore_and_rehearse; then exit 1; fi
  test "$(command cat "$events")" = $'BACKUP\nLOCK\nCAPACITY originals\nRESTORE\nCAPACITY migration'
  printf 'PASS capacity is checked again before the separate migration clone can allocate storage\n'
  hold_backup_lock(){ record LOCK_BUSY; return 1; }
  : >"$events"
  if backup_restore_and_rehearse; then exit 1; fi
  test "$(command cat "$events")" = $'BACKUP\nLOCK_BUSY'
  printf 'PASS competing backup lock fails closed before sizing, originals or clone\n'
)
restore_script="${here}/../../backup/rehearse-core-originals-restore.sh"
if command -v flock >/dev/null && command -v timeout >/dev/null && test -d /proc; then
  sed -n '/^hold_backup_lock(){/,/^}/p' "$script" >"$fixture/lock-functions"
  sed -n '/^acquire_backup_lock(){/,/^}/p' "$restore_script" >>"$fixture/lock-functions"
  sed "s|/run/lock/hotel-ai-os-backup.lock|$fixture/backup.lock|g" "$fixture/lock-functions" >"$fixture/isolated-lock-functions"
  (
    exec 9>"$fixture/backup.lock"; flock -n 9
    if bash -c 'source "$1"; fail(){ return 1; }; hold_backup_lock' _ "$fixture/isolated-lock-functions"; then exit 1; fi
    timeout 3 bash -c 'source "$1"; acquire_backup_lock' _ "$fixture/isolated-lock-functions"
    flock -u 9; exec 9>&-
    timeout 3 bash -c 'source "$1"; acquire_backup_lock' _ "$fixture/isolated-lock-functions"
  )
  printf 'PASS actual lock competition blocks, inherited FD9 avoids deadlock, standalone restore still locks\n'
else
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) printf 'SKIP actual Linux flock/FD inheritance contract on Windows; Ubuntu CI must run it\n' ;;
    *) printf 'Required native flock/timeout/procfs missing\n' >&2; exit 1 ;;
  esac
fi
: >"$events"
coordinator(){ record "COORDINATOR $1"; }
ocr(){ record "OCR $1"; }
backup_restore_and_rehearse(){ record PAIRED_RESTORE_AND_CLONE_MIGRATION; }
systemctl(){ record "SYSTEMCTL $1"; }
install_candidate(){ record INSTALL_VERIFIED_CANDIDATE; }
migrate_live(){ record MIGRATE_LIVE_FLYWAY_ONLY; }
health(){ record CORE_HEALTH; }
archive_stage(){ record ARCHIVE_PRIVATE_STAGE; }
final_audit(){ record FINAL_AUDIT_MARK_FULL; printf 'FINAL_SERVER_AUDIT_OK\n'; }
cat(){ if test "$1" = /var/lib/hotel-ai-os-deploy/deployed-source-commit; then printf '%s\n' "$expected_source_commit"; else command cat "$@"; fi; }
perform_full_release >"$fixture/result"
expected=$'COORDINATOR BEGIN_FULL\nPAIRED_RESTORE_AND_CLONE_MIGRATION\nOCR PREPARE\nSYSTEMCTL stop\nINSTALL_VERIFIED_CANDIDATE\nOCR ACTIVATE\nMIGRATE_LIVE_FLYWAY_ONLY\nSYSTEMCTL start\nCORE_HEALTH\nARCHIVE_PRIVATE_STAGE\nFINAL_AUDIT_MARK_FULL\nOCR COMMIT'
test "$(command cat "$events")" = "$expected"
test "$begun/$switched/$migration_started/$audited" = true/true/true/true
printf 'PASS FULL order includes original restore and isolated migrations before/after cutover\n'

# Only failure-state protocol is mocked; no database, /etc, service or production paths are changed.
: >"$events"; begun=true; audited=false; switched=false; migration_started=false; ocr_prepared=true; ocr_activation_started=true; core_stop_started=true
rollback
test "$(command cat "$events")" = $'SYSTEMCTL stop\nOCR ROLLBACK\nSYSTEMCTL start\nCORE_HEALTH\nCOORDINATOR ABORT_FULL'
printf 'PASS pre-migration failure restores OCR and proves old Core health before ABORT_FULL\n'
: >"$events"; ocr_activation_started=false
rollback
test "$(command cat "$events")" = $'SYSTEMCTL stop\nSYSTEMCTL start\nCORE_HEALTH\nCOORDINATOR ABORT_FULL'
printf 'PASS installer failure after OCR PREPARE does not invoke an unsupported PREPARED rollback\n'
: >"$events"; core_stop_started=false
rollback
test "$(command cat "$events")" = $'CORE_HEALTH\nCOORDINATOR ABORT_FULL'
printf 'PASS pre-cutover backup failure keeps the active Core running while proving the baseline\n'
: >"$events"; ocr_activation_started=true; core_stop_started=true
: >"$events"; migration_started=true
if rollback 2>"$fixture/maintenance"; then echo 'Must not restart old code after live DDL' >&2;exit 1;fi
test "$(command cat "$events")" = $'SYSTEMCTL stop\nOCR ROLLBACK'
grep -Fq FULL_CI_MAINTENANCE_REQUIRED "$fixture/maintenance"
printf 'PASS live migration failure preserves database and maintenance marker, no speculative old Core restart\n'
: >"$events"; audited=true
if rollback 2>"$fixture/committed"; then exit 1;fi
test ! -s "$events"
grep -Fq FULL_CI_VERIFIED_RELEASE_ACTIVE "$fixture/committed"
printf 'PASS post-audit cleanup error never silently rolls back an already-marked healthy release\n'
grep -q 'O_NOFOLLOW' "${here}/../full-ci-release.py"
grep -q 'user=identity.pw_uid' "${here}/../full-ci-release.py"
grep -q 'ignoreMigrationPatterns(new String\[0\])' "${here}/../FullCiFlyway.java"
! grep -Eq 'SpringApplication|springframework|server.port' "${here}/../FullCiFlyway.java"
printf 'PASS candidates run restricted Flyway-only, without Spring workers or root application execution\n'
trap - EXIT; cleanup_fixture
