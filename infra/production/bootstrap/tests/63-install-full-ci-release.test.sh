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
