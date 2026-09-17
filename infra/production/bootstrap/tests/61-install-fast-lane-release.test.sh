#!/usr/bin/env bash
set -Eeuo pipefail

test_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
installer="$(cd -- "${test_dir}/.." && pwd)/61-install-fast-lane-release.sh"
deployer_bootstrap="$(cd -- "${test_dir}/.." && pwd)/62-configure-fast-lane-deployer.sh"
full_installer="$(cd -- "${test_dir}/.." && pwd)/60-install-release.sh"
final_audit="$(cd -- "${test_dir}/.." && pwd)/100-final-audit.sh"

# shellcheck source=../61-install-fast-lane-release.sh
source "${installer}"

passed=0
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/hotel-ai-fast-lane-test.XXXXXXXX")"
cleanup_test() {
  local resolved_fixture
  local resolved_temp
  resolved_fixture="$(realpath -- "${fixture_root}")"
  resolved_temp="$(realpath -- "${TMPDIR:-/tmp}")"
  case "${resolved_fixture}" in
    "${resolved_temp}"/hotel-ai-fast-lane-test.*) rm -rf --one-file-system -- "${resolved_fixture}" ;;
    *) printf 'Refusing to remove unexpected test path: %s\n' "${resolved_fixture}" >&2 ;;
  esac
}
trap cleanup_test EXIT

ok() {
  passed=$((passed + 1))
  printf 'ok %d - %s\n' "${passed}" "$1"
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >"${fixture_root}/stdout" 2>"${fixture_root}/stderr"; then
    printf 'not ok - %s unexpectedly succeeded\n' "${description}" >&2
    exit 1
  fi
  ok "${description}"
}

expect_status() {
  local expected="$1"
  local description="$2"
  shift 2
  local actual=0
  "$@" >"${fixture_root}/stdout" 2>"${fixture_root}/stderr" || actual=$?
  if test "${actual}" -ne "${expected}"; then
    printf 'not ok - %s returned %s, expected %s\n' "${description}" "${actual}" "${expected}" >&2
    cat "${fixture_root}/stderr" >&2
    exit 1
  fi
  ok "${description}"
}

bash -n "${installer}"
ok 'installer has valid Bash syntax'
test "$(sed -n '1p' "${installer}")" = '#!/bin/bash'
ok 'sudo-executed installer pins its root interpreter'
bash -n "${deployer_bootstrap}"
ok 'dedicated deployer bootstrap has valid Bash syntax'
test "$(sed -n '1p' "${deployer_bootstrap}")" = '#!/bin/bash'
ok 'root bootstrap pins its interpreter'
bash -n "${full_installer}"
bash -n "${final_audit}"
ok 'complete release install and final audit scripts have valid Bash syntax'

validate_release_id '20260917-pilot8-abc1234'
ok 'safe release id is accepted'
expect_failure 'release id traversal is rejected' validate_release_id 'release..escape'
expect_failure 'uppercase SHA-256 is rejected' validate_sha256 test \
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
validate_sha256 test 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
ok 'lowercase SHA-256 is accepted'
validate_artifact_size test 1 1024
ok 'positive artifact size within its bound is accepted'
expect_failure 'zero-byte artifact size is rejected' validate_artifact_size test 0 1024
expect_failure 'oversized artifact is rejected' validate_artifact_size test 1025 1024
validate_source_commit test 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
ok 'lowercase full Git commit is accepted'
expect_failure 'short Git commit is rejected' validate_source_commit test 'aaaaaaaaaaaa'
expect_failure 'uppercase Git commit is rejected' validate_source_commit test \
  'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'

python3 - "${fixture_root}" <<'PY'
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
migrations = {
    "BOOT-INF/classes/db/migration/V1__foundation.sql": b"select 1;\n",
    "BOOT-INF/classes/db/migration/V2__feature.sql": b"select 2;\n",
}

for filename, entries in (
    ("a.jar", list(migrations.items())),
    ("reordered.jar", list(reversed(list(migrations.items())))),
    (
        "changed.jar",
        [
            ("BOOT-INF/classes/db/migration/V1__foundation.sql", b"select 1;\n"),
            ("BOOT-INF/classes/db/migration/V2__feature.sql", b"select 200;\n"),
        ],
    ),
):
    with zipfile.ZipFile(root / filename, "w") as archive:
        for name, content in entries:
            archive.writestr(name, content)

with zipfile.ZipFile(root / "web.zip", "w") as archive:
    archive.writestr("index.html", b"<!doctype html><title>pilot</title>\n")
    archive.writestr("assets/app.js", b"console.log('pilot');\n")

with zipfile.ZipFile(root / "unsafe.zip", "w") as archive:
    archive.writestr("../escape.txt", b"escape")
PY

identity_a="$(read_migration_identity "${fixture_root}/a.jar")"
identity_reordered="$(read_migration_identity "${fixture_root}/reordered.jar")"
identity_changed="$(read_migration_identity "${fixture_root}/changed.jar")"
test "${identity_a}" = "${identity_reordered}"
ok 'migration fingerprint is independent of ZIP entry order'
test "${identity_a}" != "${identity_changed}"
ok 'migration fingerprint changes when migration content changes'
read -r migration_version migration_fingerprint <<<"${identity_a}"
test "${migration_version}" = '2'
[[ "${migration_fingerprint}" =~ ^[0-9a-f]{64}$ ]]
ok 'migration identity contains highest version and lowercase SHA-256'
migration_one_sha="$(printf 'select 1;\n' | sha256sum | cut -d' ' -f1)"
migration_two_sha="$(printf 'select 2;\n' | sha256sum | cut -d' ' -f1)"
expected_state="BOOT-INF/classes/db/migration/V1__foundation.sql:${migration_one_sha}
BOOT-INF/classes/db/migration/V2__feature.sql:${migration_two_sha}"
expected_fingerprint="$(printf '%s' "${expected_state}" | sha256sum | cut -d' ' -f1)"
test "${migration_fingerprint}" = "${expected_fingerprint}"
ok 'migration fingerprint uses the documented sorted-entry algorithm without trailing LF'

mkdir "${fixture_root}/python-cwd"
cat >"${fixture_root}/python-cwd/sitecustomize.py" <<'PY'
from pathlib import Path
Path("sitecustomize-loaded").write_text("unsafe import\n", encoding="utf-8")
PY
(
  cd "${fixture_root}/python-cwd"
  read_migration_identity "${fixture_root}/a.jar" >/dev/null
)
test ! -e "${fixture_root}/python-cwd/sitecustomize-loaded"
for production_script in "${installer}" "${full_installer}" "${final_audit}"; do
  if grep -Eq 'python3[[:space:]]+-([[:space:]]|$)' "${production_script}"; then
    printf 'not ok - production inline Python is not isolated: %s\n' "${production_script}" >&2
    exit 1
  fi
done
grep -q 'python3 -I -' "${installer}"
grep -q 'python3 -I -' "${full_installer}"
grep -q 'python3 -I -' "${final_audit}"
ok 'production inline Python ignores writable cwd sitecustomize modules'

require_unchanged_migrations \
  "${migration_version}" "${migration_fingerprint}" \
  "${migration_version}" "${migration_fingerprint}"
ok 'unchanged migration identity passes BACKEND gate'
expect_status 4 'changed migration identity requires FULL deployment' \
  require_unchanged_migrations \
  "${migration_version}" 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  "${migration_version}" "${migration_fingerprint}"
grep -q 'FAST_LANE_FULL_REQUIRED' "${fixture_root}/stderr"
grep -q '"next":"FULL"' "${fixture_root}/stderr"
ok 'migration gate emits stable machine-readable block reason'

mkdir "${fixture_root}/web-extracted"
safe_extract_web_bundle "${fixture_root}/web.zip" "${fixture_root}/web-extracted"
test -f "${fixture_root}/web-extracted/index.html"
test -f "${fixture_root}/web-extracted/assets/app.js"
ok 'safe Web ZIP extracts into the unique destination'
web_index_sha="$(sha256sum "${fixture_root}/web-extracted/index.html" | cut -d' ' -f1)"
web_script_sha="$(sha256sum "${fixture_root}/web-extracted/assets/app.js" | cut -d' ' -f1)"
web_index_size="$(wc -c <"${fixture_root}/web-extracted/index.html" | tr -d '[:space:]')"
web_script_size="$(wc -c <"${fixture_root}/web-extracted/assets/app.js" | tr -d '[:space:]')"
web_tree_state="assets/app.js:${web_script_size}:${web_script_sha}
index.html:${web_index_size}:${web_index_sha}"
expected_web_tree_sha="$(printf '%s' "${web_tree_state}" | sha256sum | cut -d' ' -f1)"
test "$(read_web_tree_fingerprint "${fixture_root}/web-extracted")" = "${expected_web_tree_sha}"
ok 'Web tree fingerprint covers every extracted file using the manifest algorithm'
mkdir "${fixture_root}/unsafe-extracted"
expect_failure 'ZIP traversal entry is rejected' \
  safe_extract_web_bundle "${fixture_root}/unsafe.zip" "${fixture_root}/unsafe-extracted"
test ! -e "${fixture_root}/escape.txt"
ok 'unsafe ZIP does not write outside extraction root'

if ln -s "${fixture_root}/a.jar" "${fixture_root}/runner-link" 2>/dev/null &&
    test -L "${fixture_root}/runner-link"; then
  expect_failure 'symlink stage leaf is rejected before hashing or copying' \
    require_regular_readable_file 'staged runner' "${fixture_root}/runner-link"
else
  passed=$((passed + 1))
  printf 'ok %d - symlink stage leaf is rejected before hashing or copying # SKIP symlinks unavailable\n' "${passed}"
fi

inspect_output="$(bash "${installer}" INSPECT "${fixture_root}/a.jar")"
grep -q '^FAST_LANE_MIGRATION_INSPECT ' <<<"${inspect_output}"
grep -q '"event":"fast_lane_migration_inspect"' <<<"${inspect_output}"
grep -q 'sha256(sorted-entry-name-colon-content-sha256-v1)' <<<"${inspect_output}"
tail -n 1 <<<"${inspect_output}" | python3 -c 'import json, sys; json.load(sys.stdin)'
ok 'INSPECT emits stable marker and JSON with algorithm name'

expect_status 3 'FULL fast path is deliberately blocked' bash "${installer}" FULL
grep -q 'FAST_LANE_FULL_BLOCKED' "${fixture_root}/stderr"
grep -q 'approved_full_deployment_workflow' "${fixture_root}/stderr"
tail -n 1 "${fixture_root}/stderr" | python3 -c 'import json, sys; json.load(sys.stdin)'
ok 'FULL block explains the approved full workflow'

mkdir -p "${fixture_root}/releases/old" "${fixture_root}/releases/new"
ln -s "${fixture_root}/releases/old" "${fixture_root}/current"
if test -L "${fixture_root}/current"; then
  test "$(validate_current_link "${fixture_root}/current" "${fixture_root}/releases")" = \
    "${fixture_root}/releases/old"
  switch_link_atomically "${fixture_root}/releases/new" "${fixture_root}/current"
  test "$(readlink -f "${fixture_root}/current")" = "${fixture_root}/releases/new"
  test -d "${fixture_root}/releases/old"
  ok 'atomic switch retains the previous immutable release'
else
  passed=$((passed + 1))
  printf 'ok %d - atomic switch retains the previous immutable release # SKIP symlinks unavailable\n' "${passed}"
fi

web_function="$(declare -f install_web_only)"
if grep -Eq 'systemctl|psql|BACKEND_CURRENT_LINK|BACKEND_RELEASE_ROOT|core-api' <<<"${web_function}"; then
  printf '%s\n' 'not ok - WEB_ONLY function references Core or database operations' >&2
  exit 1
fi
ok 'WEB_ONLY implementation contains no Core or database operation'

backend_function="$(declare -f install_backend_only)"
if grep -Eq 'hotel-ai-os-migrate|MIGRATION_SERVICE|systemctl start|psql' <<<"${backend_function}"; then
  printf '%s\n' 'not ok - BACKEND function tries to run migrations or access the database' >&2
  exit 1
fi
gate_line="$(grep -n 'require_unchanged_migrations' <<<"${backend_function}" | tail -n 1 | cut -d: -f1)"
switch_line="$(grep -n 'switch_link_atomically' <<<"${backend_function}" | tail -n 1 | cut -d: -f1)"
test "${gate_line}" -lt "${switch_line}"
ok 'BACKEND migration gate runs before atomic link switch'
first_health_line="$(grep -n 'wait_for_core_release' <<<"${backend_function}" | head -n 1 | cut -d: -f1)"
last_health_line="$(grep -n 'wait_for_core_release' <<<"${backend_function}" | tail -n 1 | cut -d: -f1)"
test "${first_health_line}" -lt "${switch_line}"
test "${last_health_line}" -gt "${switch_line}"
ok 'BACKEND verifies Core health before and after the switch'

prepare_function="$(declare -f prepare_common_release_state)"
lock_line="$(grep -n 'acquire_release_lock' <<<"${prepare_function}" | tail -n 1 | cut -d: -f1)"
state_line="$(grep -n 'read_deployed_source_commit' <<<"${prepare_function}" | tail -n 1 | cut -d: -f1)"
test "${lock_line}" -lt "${state_line}"
grep -q 'PILOT_EXPECTED_SOURCE_COMMIT' <<<"${prepare_function}"
grep -q 'PILOT_SOURCE_COMMIT' <<<"${prepare_function}"
ok 'deployed source state is checked under the release lock'
begin_full_function="$(declare -f begin_full_release)"
mark_full_function="$(declare -f mark_verified_full_release)"
read_full_function="$(declare -f read_full_release_in_progress)"
write_full_function="$(declare -f write_full_release_in_progress)"
grep -q 'write_full_release_in_progress' <<<"${begin_full_function}"
grep -q 'read_full_release_in_progress' <<<"${mark_full_function}"
grep -q 'FULL_RELEASE_IN_PROGRESS_FILE' <<<"$(declare -f read_deployed_source_commit)"
grep -q 'BEGIN_FULL.*source_commit.*release_id' "${full_installer}"
grep -q 'MARK_FULL.*verified_full_commit.*verified_full_release_id' "${final_audit}"
grep -q '\.source-commit' "${full_installer}"
grep -q '\.release-id' "${full_installer}"
grep -q 'backend source identity differs' <<<"${mark_full_function}"
if grep -q 'FULL_RELEASE_ALREADY_IN_PROGRESS' <<<"${begin_full_function}"; then
  printf '%s\n' 'not ok - repeated BEGIN_FULL could admit concurrent complete releases' >&2
  exit 1
fi
ok 'complete releases block fast lanes until the verified final audit advances the baseline'

max_release_id="r$(printf 'a%.0s' {1..127})"
max_previous_backend="${BACKEND_RELEASE_ROOT}/${max_release_id}"
max_previous_web="${WEB_RELEASE_ROOT}/${max_release_id}"
validate_release_id "${max_release_id}"
validate_absolute_path 'maximum previous Backend path' "${max_previous_backend}"
validate_absolute_path 'maximum previous Web path' "${max_previous_web}"
max_full_record="$(printf '%s %s %s %s' \
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "${max_release_id}" \
  "${max_previous_backend}" "${max_previous_web}")"
test "$(printf '%s\n' "${max_full_record}" | wc -c)" -le "${FULL_RELEASE_MARKER_MAX_BYTES}"
read -r parsed_commit parsed_release parsed_backend parsed_web parsed_trailing <<<"${max_full_record}"
test "${parsed_commit}" = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
test "${parsed_release}" = "${max_release_id}"
test "${parsed_backend}" = "${max_previous_backend}"
test "${parsed_web}" = "${max_previous_web}"
test -z "${parsed_trailing:-}"
grep -q 'FULL_RELEASE_MARKER_MAX_BYTES' <<<"${read_full_function}"
grep -q 'FULL_RELEASE_MARKER_MAX_BYTES' <<<"${write_full_function}"
ok 'four-field FULL marker accepts maximum valid release identities within one shared bound'
quarantine_function="$(declare -f quarantine_stage_payload)"
grep -q 'mv --no-target-directory' <<<"${quarantine_function}"
grep -q 'FAST_STAGE_QUARANTINE' <<<"${quarantine_function}"
snapshot_function="$(declare -f snapshot_stage_artifact)"
grep -q 'count_bytes' <<<"${snapshot_function}"
grep -q 'expected_size' <<<"${snapshot_function}"
grep -q 'verify_sha256' <<<"${snapshot_function}"
grep -q 'chown --no-dereference root:root.*quarantined_stage_root' <<<"${quarantine_function}"
ok 'untrusted staging is root-quarantined then size-bounded and hash-verified'

grep -q 'hotel-ai-deploy ALL=(root) NOPASSWD: HOTEL_AI_OS_FAST_RELEASE' "${deployer_bootstrap}"
grep -q 'secure_path="/usr/sbin:/usr/bin:/sbin:/bin"' "${deployer_bootstrap}"
grep -q 'hotel-ai-os-install-fast-lane-release POLICY' "${deployer_bootstrap}"
if grep -q 'NOPASSWD:ALL' "${deployer_bootstrap}"; then
  printf '%s\n' 'not ok - dedicated deployer bootstrap grants unrestricted sudo' >&2
  exit 1
fi
ok 'dedicated deployer sudo contract is limited to the fixed installer'

grep -q 'root:${DEPLOY_GROUP}:750' "${deployer_bootstrap}"
grep -q 'root:root:644' "${deployer_bootstrap}"
grep -q "dedicated deploy authorized_keys" "${deployer_bootstrap}"
grep -q 'require_root_controlled_parent_chain' "${deployer_bootstrap}"
grep -q 'trusted_installer_snapshot' "${deployer_bootstrap}"
grep -q 'non-root numeric UID' "${deployer_bootstrap}"
grep -q 'unexpected passwd home' "${deployer_bootstrap}"
grep -q 'unexpected login shell' "${deployer_bootstrap}"
grep -q 'must not have supplementary groups' "${deployer_bootstrap}"
grep -q 'password must remain locked' "${deployer_bootstrap}"
if grep -Eq 'chown "?\$\{DEPLOY_USER\}.*DEPLOY_HOME|chmod 0?7[0-7][2367].*DEPLOY_HOME' "${deployer_bootstrap}"; then
  printf '%s\n' 'not ok - dedicated deployer can mutate its SSH authorization files' >&2
  exit 1
fi
ok 'SSH home and authorized_keys remain root controlled and reject symlink replacement'

grep -q "existing fast staging root is not a recognized safe bootstrap state" "${deployer_bootstrap}"
preflight_function="$(sed -n '/^validate_preexisting_layout()/,/^}/p' "${deployer_bootstrap}")"
grep -q 'recognized safe bootstrap state' <<<"${preflight_function}"
grep -q 'must either both exist or both be absent' <<<"${preflight_function}"
grep -q 'existing global sudoers policy is invalid' <<<"${preflight_function}"
baseline_validation_line="$(grep -n 'existing deployed-source-commit differs' "${deployer_bootstrap}" | head -n 1 | cut -d: -f1)"
active_install_line="$(grep -n '^[[:space:]]*activate_installer_and_sudoers[[:space:]]*$' "${deployer_bootstrap}" | tail -n 1 | cut -d: -f1)"
test "${baseline_validation_line}" -lt "${active_install_line}"
grep -q 'acquire_release_lock' "${deployer_bootstrap}"
ok 'bootstrap validates the existing layout and baseline under the shared lock before activation'

bootstrap_baseline_function="$(bash -c \
  'source "$1"; declare -f verify_active_baseline' _ "${deployer_bootstrap}")"
bootstrap_link_function="$(bash -c \
  'source "$1"; declare -f validate_active_release_link' _ "${deployer_bootstrap}")"
grep -q 'dirname --.*target' <<<"${bootstrap_link_function}"
grep -q 'read_root_owned_source_identity' <<<"${bootstrap_baseline_function}"
grep -q '/proc/.*main_pid.*/cwd' <<<"${bootstrap_baseline_function}"
grep -q 'CORE_HEALTH_URL' <<<"${bootstrap_baseline_function}"
grep -q 'WEB_HEALTH_URL' <<<"${bootstrap_baseline_function}"
grep -q 'served_index_sha.*current_index_sha' <<<"${bootstrap_baseline_function}"
baseline_proof_line="$(grep -n '^[[:space:]]*verify_active_baseline .*baseline_commit' "${deployer_bootstrap}" | tail -n 1 | cut -d: -f1)"
snapshot_line="$(grep -n '^[[:space:]]*capture_trusted_input_snapshots[[:space:]]*$' "${deployer_bootstrap}" | tail -n 1 | cut -d: -f1)"
account_mutation_line="$(grep -n '^[[:space:]]*groupadd --system' "${deployer_bootstrap}" | tail -n 1 | cut -d: -f1)"
state_mutation_line="$(grep -n '^[[:space:]]*prepare_baseline_state[[:space:]]*$' "${deployer_bootstrap}" | tail -n 1 | cut -d: -f1)"
test "${baseline_proof_line}" -lt "${snapshot_line}"
test "${baseline_proof_line}" -lt "${account_mutation_line}"
test "${baseline_proof_line}" -lt "${state_mutation_line}"
ok 'bootstrap proves both current releases, running Core, and served Web before mutation'

activation_function="$(bash -c \
  'source "$1"; declare -f activate_installer_and_sudoers' _ "${deployer_bootstrap}")"
rollback_function="$(bash -c \
  'source "$1"; declare -f rollback_activation_transaction' _ "${deployer_bootstrap}")"
revoke_line="$(grep -n 'SUDOERS_FILE.*sudoers_backup_pending' <<<"${activation_function}" | head -n 1 | cut -d: -f1)"
install_line="$(grep -n 'installer_pending.*INSTALLED_COMMAND' <<<"${activation_function}" | head -n 1 | cut -d: -f1)"
test "${revoke_line}" -lt "${install_line}"
grep -q 'activation_transaction_armed=true' <<<"${activation_function}"
grep -q 'activation_had_installer.*false' <<<"${rollback_function}"
grep -q 'rm -f --.*INSTALLED_COMMAND' <<<"${rollback_function}"
grep -q 'activation_had_sudoers.*false' <<<"${rollback_function}"
grep -q 'rm -f --.*SUDOERS_FILE' <<<"${rollback_function}"
grep -q 'preserve_activation_recovery_files=true' <<<"${rollback_function}"
grep -q 'FAST_LANE_BOOTSTRAP_RECOVERY_PATHS' <<<"${rollback_function}"
cleanup_bootstrap_function="$(bash -c \
  'source "$1"; declare -f cleanup_pending_files' _ "${deployer_bootstrap}")"
grep -q 'preserve_activation_recovery_files' <<<"${cleanup_bootstrap_function}"
grep -q 'rollback_activation_transaction' "${deployer_bootstrap}"
if grep -Eq 'install .*PREVIOUS_INSTALLED_COMMAND.*INSTALLED_COMMAND' "${deployer_bootstrap}"; then
  printf '%s\n' 'not ok - bootstrap can activate a stale previous installer' >&2
  exit 1
fi
ok 'installer and sudoers activate transactionally and absence rolls back to absence'
grep -q "prepare_deploy_state_layout" "${deployer_bootstrap}"
if grep -q 'install -d.*DEPLOY_STATE_DIRECTORY' "${deployer_bootstrap}"; then
  printf '%s\n' 'not ok - deploy state bootstrap may follow an existing symlink' >&2
  exit 1
fi
ok 'bootstrap creates or validates fixed state directories without following symlinks'

cleanup_function="$(declare -f cleanup_verified_stage_after_success)"
grep -q 'cleanup_status=1' <<<"${cleanup_function}"
grep -q 'return "${cleanup_status}"' <<<"${cleanup_function}"
grep -q 'cleanup_verified_stage_after_success' <<<"$(declare -f cleanup_incomplete_state)"
grep -q 'find -P.*-xdev.*-delete' <<<"$(declare -f remove_private_stage_tree)"
ok 'success and failure both remove bounded private staging trees'

grep -q 'write_deployed_source_commit.*source_commit' <<<"${web_function}"
grep -q 'write_deployed_source_commit.*source_commit' <<<"${backend_function}"
ok 'successful fast lanes persist the target source commit'

web_write_line="$(grep -n 'write_deployed_source_commit.*source_commit' <<<"${web_function}" | cut -d: -f1)"
web_arm_line="$(grep -n 'source_commit_updated=true' <<<"${web_function}" | cut -d: -f1)"
backend_write_line="$(grep -n 'write_deployed_source_commit.*source_commit' <<<"${backend_function}" | cut -d: -f1)"
backend_arm_line="$(grep -n 'source_commit_updated=true' <<<"${backend_function}" | cut -d: -f1)"
test "${web_arm_line}" -lt "${web_write_line}"
test "${backend_arm_line}" -lt "${backend_write_line}"
ok 'marker restoration is armed before each atomic state write'

failure_function="$(declare -f failure_handler)"
grep -q 'verify_web_is_served.*previous_web_index_sha' <<<"${failure_function}"
grep -q 'mark_deploy_state_inconsistent' <<<"${failure_function}"
ok 'rollback verifies the restored Web release and fails closed on state inconsistency'

printf '1..%d\n' "${passed}"
