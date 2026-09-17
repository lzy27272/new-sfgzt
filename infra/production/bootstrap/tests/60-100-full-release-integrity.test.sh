#!/usr/bin/env bash
set -Eeuo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bootstrap_dir="$(cd -- "${test_dir}/.." && pwd)"
full_installer="${bootstrap_dir}/60-install-release.sh"
final_audit="${bootstrap_dir}/100-final-audit.sh"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/hotel-ai-full-integrity-test.XXXXXXXX")"
passed=0

cleanup_test() {
  local resolved_fixture
  local resolved_temp
  resolved_fixture="$(realpath -- "${fixture_root}")"
  resolved_temp="$(realpath -- "${TMPDIR:-/tmp}")"
  case "${resolved_fixture}" in
    "${resolved_temp}"/hotel-ai-full-integrity-test.*)
      rm -rf --one-file-system -- "${resolved_fixture}"
      ;;
    *)
      printf 'Refusing to remove unexpected test path: %s\n' "${resolved_fixture}" >&2
      ;;
  esac
}
trap cleanup_test EXIT

ok() {
  passed=$((passed + 1))
  printf 'ok %d - %s\n' "${passed}" "$1"
}

bash -n "${full_installer}"
bash -n "${final_audit}"
ok 'complete installer and final audit have valid Bash syntax'

grep -q "^PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'$" "${full_installer}"
grep -q "^PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'$" "${final_audit}"
ok 'root-run release scripts pin a fixed command search path'

grep -q '<migration-runner-sha256> <index-sha256> <web-tree-sha256>' "${full_installer}"
grep -q "verify_sha256 'staged migration runner'" "${full_installer}"
grep -q "verify_sha256 'installed migration runner'" "${full_installer}"
grep -q 'read_web_tree_fingerprint.*STAGE_ROOT' "${full_installer}"
grep -q 'read_web_tree_fingerprint.*web_release' "${full_installer}"
ok 'complete installer verifies all four artifact identities before and after copy'

grep -q '^validate_trusted_stage_tree()' "${full_installer}"
grep -q 'Every release stage node must be owned by root:root' "${full_installer}"
grep -q 'Release stage files must have exactly one hard link' "${full_installer}"
grep -q 'install -d -o root -g root -m 0700' "${full_installer}"
grep -q '^cleanup_failed_candidate()' "${full_installer}"
cleanup_disarm_line="$(grep -n 'candidate_cleanup_armed=false' "${full_installer}" | tail -n 1 | cut -d: -f1)"
first_switch_line="$(grep -n 'ln -sfn.*backend_release' "${full_installer}" | tail -n 1 | cut -d: -f1)"
test "${cleanup_disarm_line}" -lt "${first_switch_line}"
ok 'untrusted staging is root-controlled and failed private candidates are cleaned before activation'

for metadata_name in \
  .core-api.sha256 \
  .migration-runner.sha256 \
  .web-index.sha256 \
  .web-tree.sha256 \
  .web-tree-fingerprint-algorithm; do
  grep -q "${metadata_name}" "${full_installer}"
  grep -q "${metadata_name}" "${final_audit}"
done
ok 'complete release persists and consumes immutable artifact metadata'

grep -q "verify_sha256 'Active migration runner'" "${final_audit}"
grep -q 'read_web_tree_fingerprint.*web_target' "${final_audit}"
grep -q 'verify_served_index_sha256.*expected_index_sha' "${final_audit}"
grep -q -- "--resolve 'www.sfgzt.cn:443:127.0.0.1'" "${final_audit}"
grep -q -- "--header 'Accept-Encoding: identity'" "${final_audit}"
verify_line="$(grep -n 'verify_full_release_integrity.*verified_full_commit' "${final_audit}" | tail -n 1 | cut -d: -f1)"
mark_line="$(grep -n 'MARK_FULL.*verified_full_commit.*verified_full_release_id' "${final_audit}" | tail -n 1 | cut -d: -f1)"
test "${verify_line}" -lt "${mark_line}"
ok 'final audit verifies active runner and Web tree before marking the baseline'

(
  trap - EXIT
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  curl() { printf '%s' 'served-index'; }
  expected_served_sha="$(printf '%s' 'served-index' | sha256sum --binary | awk '{print $1}')"
  verify_served_index_sha256 "${expected_served_sha}"
  curl() { printf '%s' 'tampered-index'; }
  if verify_served_index_sha256 "${expected_served_sha}" >/dev/null 2>&1; then
    printf '%s\n' 'not ok - served Web body hash mismatch was accepted' >&2
    exit 1
  fi
)
ok 'HTTPS verification hashes the served response body and rejects mismatches'

grep -q '^read_full_release_in_progress()' "${final_audit}"
grep -q 'previous_backend previous_web' "${final_audit}"
grep -q 'exactly four fields' "${final_audit}"
grep -q "'Usage: 100-final-audit.sh'" "${final_audit}"
if grep -q 'verified-full-source-commit' "${final_audit}"; then
  printf '%s\n' 'not ok - final audit still requires manual FULL identity arguments' >&2
  exit 1
fi
ok 'final audit safely derives FULL identity from the root-owned sentinel'

(
  trap - EXIT
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  validate_full_marker_size 1024
  if validate_full_marker_size 1025 >/dev/null 2>&1; then
    printf '%s\n' 'not ok - oversized FULL marker was accepted' >&2
    exit 1
  fi
)
ok 'FULL marker size accepts the 1024-byte boundary and rejects 1025 bytes'

mkdir -p "${fixture_root}/backups/daily"
printf '%s' legacy >"${fixture_root}/backups/hotel_ai_os-auto-20260916T010000+0800.dump.enc"
printf '%s' retained >"${fixture_root}/backups/daily/hotel_ai_os-daily-20260917T010000+0800.dump.enc"
(cd "${fixture_root}/backups/daily" && \
  sha256sum hotel_ai_os-daily-20260917T010000+0800.dump.enc \
    >hotel_ai_os-daily-20260917T010000+0800.dump.enc.sha256)
touch -t 202609160100 "${fixture_root}/backups/hotel_ai_os-auto-20260916T010000+0800.dump.enc"
touch -t 202609170100 "${fixture_root}/backups/daily/hotel_ai_os-daily-20260917T010000+0800.dump.enc"
latest_backup="$({
  trap - EXIT
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  select_latest_local_backup "${fixture_root}/backups"
})"
test "${latest_backup}" = \
  "${fixture_root}/backups/daily/hotel_ai_os-daily-20260917T010000+0800.dump.enc"
(
  trap - EXIT
  cd "${fixture_root}"
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  verify_local_backup_checksum "${latest_backup}"
)
rm "${fixture_root}/backups/daily/hotel_ai_os-daily-20260917T010000+0800.dump.enc"
latest_backup="$({
  trap - EXIT
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  select_latest_local_backup "${fixture_root}/backups"
})"
test "${latest_backup}" = \
  "${fixture_root}/backups/hotel_ai_os-auto-20260916T010000+0800.dump.enc"
ok 'final audit selects the newest retained backup with legacy fallback'

mkdir -p "${fixture_root}/web/assets"
printf '%s' '<!doctype html>' >"${fixture_root}/web/index.html"
printf '%s' 'console.log("ok")' >"${fixture_root}/web/assets/app.js"

installer_fingerprint="$({
  trap - EXIT
  # shellcheck source=../60-install-release.sh
  source "${full_installer}"
  read_web_tree_fingerprint "${fixture_root}/web" artifact
})"
printf '%s\n' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' >"${fixture_root}/web/.source-commit"
printf '%s\n' 'release-1' >"${fixture_root}/web/.release-id"
installed_fingerprint="$({
  trap - EXIT
  # shellcheck source=../60-install-release.sh
  source "${full_installer}"
  read_web_tree_fingerprint "${fixture_root}/web" installed
})"
test "${installer_fingerprint}" = "${installed_fingerprint}"
ok 'installed Web verification excludes only the two root release identity files'

if (
  trap - EXIT
  # shellcheck source=../60-install-release.sh
  source "${full_installer}"
  read_web_tree_fingerprint "${fixture_root}/web" artifact
) >/dev/null 2>&1; then
  printf '%s\n' 'not ok - staged Web artifact accepted a reserved metadata path' >&2
  exit 1
fi
ok 'staged Web artifact rejects release metadata path collisions'

audit_fingerprint="$({
  trap - EXIT
  # shellcheck source=../100-final-audit.sh
  source "${final_audit}"
  read_web_tree_fingerprint "${fixture_root}/web"
})"
test "${audit_fingerprint}" = "${installer_fingerprint}"
ok 'installer and final audit implement one Web tree fingerprint contract'

mkdir -p "${fixture_root}/symlink-web/assets"
printf '%s' '<!doctype html>' >"${fixture_root}/symlink-web/index.html"
if ln -s '../index.html' "${fixture_root}/symlink-web/assets/unsafe-link" 2>/dev/null &&
    test -L "${fixture_root}/symlink-web/assets/unsafe-link"; then
  if (
    trap - EXIT
    # shellcheck source=../100-final-audit.sh
    source "${final_audit}"
    read_web_tree_fingerprint "${fixture_root}/symlink-web"
  ) >/dev/null 2>&1; then
    printf '%s\n' 'not ok - final audit accepted a symlink in the active Web tree' >&2
    exit 1
  fi
fi
grep -q 'stat.S_ISLNK' "${final_audit}"
ok 'active Web tree verification rejects symlinks'

printf '1..%d\n' "${passed}"
