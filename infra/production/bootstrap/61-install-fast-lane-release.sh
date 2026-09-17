#!/bin/bash
set -Eeuo pipefail
shopt -s inherit_errexit

# This installer is intentionally narrower than 60-install-release.sh. It is
# for already-provisioned hosts only and never removes an existing release.
# `previous` in the success event is the old current-link target: that immutable
# directory is the retained rollback point. A second mutable /previous symlink
# is deliberately not created, so rollback has only one pointer to restore.
umask 027
export PATH='/usr/sbin:/usr/bin:/sbin:/bin'

readonly FAST_STAGE_ROOT='/tmp/hotel-ai-os-fast-release'
readonly FAST_STAGE_QUARANTINE='/tmp/hotel-ai-os-fast-release/.quarantine'
readonly INSTALLED_COMMAND='/usr/local/sbin/hotel-ai-os-install-fast-lane-release'
readonly INSTALLER_POLICY_VERSION='1'
readonly DEPLOY_USER='hotel-ai-deploy'
readonly DEPLOY_GROUP='hotel-ai-deploy'
readonly BACKEND_RELEASE_ROOT='/opt/hotel-ai-os/releases'
readonly BACKEND_CURRENT_LINK='/opt/hotel-ai-os/current'
readonly WEB_RELEASE_ROOT='/srv/www/hotel-ai-os/releases'
readonly WEB_CURRENT_LINK='/srv/www/hotel-ai-os/current'
readonly RELEASE_LOCK='/run/lock/hotel-ai-os-release.lock'
readonly DEPLOY_STATE_DIRECTORY='/var/lib/hotel-ai-os-deploy'
readonly DEPLOY_SOURCE_COMMIT_FILE='/var/lib/hotel-ai-os-deploy/deployed-source-commit'
readonly DEPLOY_STATE_INCONSISTENT_FILE='/var/lib/hotel-ai-os-deploy/INCONSISTENT'
readonly FULL_RELEASE_IN_PROGRESS_FILE='/var/lib/hotel-ai-os-deploy/FULL_RELEASE_IN_PROGRESS'
readonly FULL_RELEASE_MARKER_MAX_BYTES='1024'
readonly CORE_SERVICE='hotel-ai-os-core-api.service'
readonly CORE_HEALTH_URL='http://127.0.0.1:18080/actuator/health'
readonly WEB_HEALTH_URL='https://www.sfgzt.cn/index.html'
readonly MIGRATION_FINGERPRINT_ALGORITHM='sha256(sorted-entry-name-colon-content-sha256-v1)'
readonly WEB_TREE_FINGERPRINT_ALGORITHM='sha256(sorted-path-colon-size-colon-content-sha256-v1)'

lane=''
release_id=''
stage_root=''
requested_stage_root=''
quarantined_stage_root=''
verified_stage_root=''
staging_directory=''
pending_link=''
pending_state_file=''
health_response_file=''
previous_target=''
previous_web_index_sha=''
current_link=''
release_root=''
link_switched=false
source_commit_updated=false
expected_source_commit=''
source_commit=''

usage() {
  cat >&2 <<'EOF'
Usage:
  61-install-fast-lane-release.sh INSPECT <absolute-core-api.jar>
  hotel-ai-os-install-fast-lane-release POLICY
  hotel-ai-os-install-fast-lane-release STATUS
  hotel-ai-os-install-fast-lane-release BEGIN_FULL <target-source-commit> <release-id>  # root administrator only
  hotel-ai-os-install-fast-lane-release RESUME_FULL <target-source-commit> <release-id>  # root administrator only
  hotel-ai-os-install-fast-lane-release ABORT_FULL <target-source-commit> <release-id>  # root administrator only; only before either live link changed
  hotel-ai-os-install-fast-lane-release MARK_FULL <verified-source-commit> <release-id>  # root administrator only
  hotel-ai-os-install-fast-lane-release VERIFY WEB_ONLY <source-commit> <index-sha256> <web-tree-sha256>
  hotel-ai-os-install-fast-lane-release VERIFY BACKEND <source-commit> <jar-sha256>
  hotel-ai-os-install-fast-lane-release WEB_ONLY <release-id> <stage-root> <expected-source-commit> <source-commit> <web-zip-sha256> <web-zip-size> <index-sha256> <web-tree-sha256>
  hotel-ai-os-install-fast-lane-release BACKEND <release-id> <stage-root> <expected-source-commit> <source-commit> <jar-sha256> <jar-size> <runner-sha256> <runner-size> <migration-version> <migration-fingerprint>
  61-install-fast-lane-release.sh FULL

Stage contract (stage-root must be a unique child of /tmp/hotel-ai-os-fast-release):
  WEB_ONLY: web.zip
  BACKEND:  core-api.jar and run-migration-once.sh

FULL is deliberately blocked here. Use the existing backup, migration,
60-install-release.sh, start, smoke-test, and audit workflow instead.
EOF
}

fail() {
  printf 'FAST_LANE_RELEASE_ERROR %s\n' "$*" >&2
  return 1
}

validate_release_id() {
  local value="${1-}"
  [[ "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || {
    fail 'release id must be 1-128 safe characters and start with an alphanumeric character'
    return 1
  }
  [[ "${value}" != *'..'* ]] || {
    fail 'release id must not contain ..'
    return 1
  }
}

validate_sha256() {
  local label="${1:?SHA label is required}"
  local value="${2-}"
  [[ "${value}" =~ ^[0-9a-f]{64}$ ]] || {
    fail "${label} must be a lowercase SHA-256 digest"
    return 1
  }
}

validate_artifact_size() {
  local label="${1:?Artifact label is required}"
  local value="${2-}"
  local maximum="${3:?Maximum byte count is required}"

  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || {
    fail "${label} size must be a positive integer"
    return 1
  }
  test "${value}" -le "${maximum}" || {
    fail "${label} exceeds the approved compressed file size limit"
    return 1
  }
}

validate_source_commit() {
  local label="${1:?Commit label is required}"
  local value="${2-}"
  [[ "${value}" =~ ^[0-9a-f]{40}$ ]] || {
    fail "${label} must be a lowercase 40-character Git commit SHA"
    return 1
  }
}

read_deployed_source_commit() {
  local value

  validate_deploy_state_directory || return $?
  test ! -e "${DEPLOY_STATE_INCONSISTENT_FILE}" && test ! -L "${DEPLOY_STATE_INCONSISTENT_FILE}" || {
    fail "deploy state is marked inconsistent; administrator repair is required: ${DEPLOY_STATE_INCONSISTENT_FILE}"
    return 1
  }
  test ! -e "${FULL_RELEASE_IN_PROGRESS_FILE}" && test ! -L "${FULL_RELEASE_IN_PROGRESS_FILE}" || {
    fail "a complete release is in progress; fast release is blocked: ${FULL_RELEASE_IN_PROGRESS_FILE}"
    return 1
  }
  test -f "${DEPLOY_SOURCE_COMMIT_FILE}" && test ! -L "${DEPLOY_SOURCE_COMMIT_FILE}" || {
    fail "deployed source state is missing or not a regular file: ${DEPLOY_SOURCE_COMMIT_FILE}"
    return 1
  }
  test "$(stat --format '%u' -- "${DEPLOY_SOURCE_COMMIT_FILE}")" = '0' || {
    fail 'deployed source state must be owned by root'
    return 1
  }
  if find "${DEPLOY_SOURCE_COMMIT_FILE}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail 'deployed source state must not be group- or world-writable'
    return 1
  fi
  value="$(cat -- "${DEPLOY_SOURCE_COMMIT_FILE}")"
  validate_source_commit 'deployed source commit' "${value}" || return $?
  printf '%s\n' "${value}"
}

read_full_release_in_progress() {
  local previous_backend
  local previous_web
  local release
  local trailing
  local value

  validate_deploy_state_directory || return $?
  test -f "${FULL_RELEASE_IN_PROGRESS_FILE}" && test ! -L "${FULL_RELEASE_IN_PROGRESS_FILE}" || {
    fail "complete release marker is missing or unsafe: ${FULL_RELEASE_IN_PROGRESS_FILE}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${FULL_RELEASE_IN_PROGRESS_FILE}")" = 'root:root' || {
    fail 'complete release marker must be owned by root:root'
    return 1
  }
  if find "${FULL_RELEASE_IN_PROGRESS_FILE}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail 'complete release marker must not be group- or world-writable'
    return 1
  fi
  test "$(wc -l <"${FULL_RELEASE_IN_PROGRESS_FILE}")" -eq 1 || {
    fail 'complete release marker must contain exactly one record'
    return 1
  }
  test "$(wc -c <"${FULL_RELEASE_IN_PROGRESS_FILE}")" -le "${FULL_RELEASE_MARKER_MAX_BYTES}" || {
    fail 'complete release marker exceeds the maximum trusted record size'
    return 1
  }
  read -r value release previous_backend previous_web trailing <"${FULL_RELEASE_IN_PROGRESS_FILE}" || return $?
  test -z "${trailing}" || {
    fail 'complete release marker contains unexpected fields'
    return 1
  }
  validate_source_commit 'complete release target commit' "${value}" || return $?
  validate_release_id "${release}" || return $?
  validate_absolute_path 'previous backend release' "${previous_backend}" || return $?
  validate_absolute_path 'previous Web release' "${previous_web}" || return $?
  case "${previous_backend}" in
    "${BACKEND_RELEASE_ROOT}/"*) ;;
    *) fail 'previous backend release escaped the immutable release root'; return 1 ;;
  esac
  case "${previous_web}" in
    "${WEB_RELEASE_ROOT}/"*) ;;
    *) fail 'previous Web release escaped the immutable release root'; return 1 ;;
  esac
  printf '%s %s %s %s\n' "${value}" "${release}" "${previous_backend}" "${previous_web}"
}

write_full_release_in_progress() {
  local value="${1:?Target source commit is required}"
  local release="${2:?Release id is required}"
  local previous_backend="${3:?Previous backend release is required}"
  local previous_web="${4:?Previous Web release is required}"

  validate_source_commit 'complete release target commit' "${value}" || return $?
  validate_release_id "${release}" || return $?
  validate_absolute_path 'previous backend release' "${previous_backend}" || return $?
  validate_absolute_path 'previous Web release' "${previous_web}" || return $?
  validate_deploy_state_directory || return $?
  pending_state_file="$(mktemp --tmpdir="${DEPLOY_STATE_DIRECTORY}" '.full-release-in-progress.XXXXXXXX')"
  printf '%s %s %s %s\n' "${value}" "${release}" "${previous_backend}" "${previous_web}" >"${pending_state_file}"
  test "$(wc -c <"${pending_state_file}")" -le "${FULL_RELEASE_MARKER_MAX_BYTES}" || {
    fail 'complete release marker exceeds the maximum trusted record size'
    return 1
  }
  chown root:root "${pending_state_file}"
  chmod 0600 "${pending_state_file}"
  sync -f "${pending_state_file}"
  mv --force --no-target-directory -- "${pending_state_file}" "${FULL_RELEASE_IN_PROGRESS_FILE}"
  pending_state_file=''
}

mark_deploy_state_inconsistent() {
  local reason="${1:-unknown}"
  local pending

  # Removing the ordinary marker makes STATUS fail closed even if writing the
  # diagnostic sentinel is itself impaired (for example, a full filesystem).
  rm -f -- "${DEPLOY_SOURCE_COMMIT_FILE}"
  pending="$(mktemp --tmpdir="${DEPLOY_STATE_DIRECTORY}" '.inconsistent.XXXXXXXX')" || return 1
  printf 'reason=%s releaseId=%s lane=%s\n' "${reason}" "${release_id}" "${lane}" >"${pending}"
  chown root:root "${pending}"
  chmod 0600 "${pending}"
  mv --force --no-target-directory -- "${pending}" "${DEPLOY_STATE_INCONSISTENT_FILE}"
}

validate_deploy_state_directory() {
  test -d "${DEPLOY_STATE_DIRECTORY}" && test ! -L "${DEPLOY_STATE_DIRECTORY}" || {
    fail "deploy state directory is missing or unsafe: ${DEPLOY_STATE_DIRECTORY}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${DEPLOY_STATE_DIRECTORY}")" = 'root:root' || {
    fail 'deploy state directory must be owned by root:root'
    return 1
  }
  if find "${DEPLOY_STATE_DIRECTORY}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail 'deploy state directory must not be group- or world-writable'
    return 1
  fi
}

write_deployed_source_commit() {
  local value="${1:?Source commit is required}"

  validate_source_commit 'source commit' "${value}" || return $?
  validate_deploy_state_directory || return $?
  pending_state_file="$(mktemp --tmpdir="${DEPLOY_STATE_DIRECTORY}" '.deployed-source-commit.XXXXXXXX')"
  printf '%s\n' "${value}" >"${pending_state_file}"
  chown root:root "${pending_state_file}"
  chmod 0640 "${pending_state_file}"
  sync -f "${pending_state_file}"
  mv --force --no-target-directory -- "${pending_state_file}" "${DEPLOY_SOURCE_COMMIT_FILE}"
  pending_state_file=''
}

validate_migration_version() {
  local value="${1-}"
  [[ "${value}" =~ ^[1-9][0-9]*$ ]] || {
    fail 'migration version must be a positive integer'
    return 1
  }
}

validate_absolute_path() {
  local label="${1:?Path label is required}"
  local value="${2-}"
  local canonical

  [[ "${value}" =~ ^/[A-Za-z0-9._/-]+$ ]] || {
    fail "${label} must be an absolute path containing only safe characters"
    return 1
  }
  [[ "${value}" != '/' && "${value}" != *'/./'* && "${value}" != *'/../'* &&
      "${value}" != */. && "${value}" != */.. && "${value}" != *'//'* ]] || {
    fail "${label} must be a normalized non-root path"
    return 1
  }
  if ! canonical="$(realpath --canonicalize-missing -- "${value}")"; then
    fail "${label} could not be canonicalized"
    return 1
  fi
  test "${canonical}" = "${value}" || {
    fail "${label} must already be canonical"
    return 1
  }
}

require_regular_readable_file() {
  local label="${1:?File label is required}"
  local path="${2-}"
  test -f "${path}" && test ! -L "${path}" && test -r "${path}" || {
    fail "${label} is not a readable non-symlink regular file: ${path}"
    return 1
  }
}

sha256_file() {
  local path="${1:?File path is required}"
  local output
  if ! output="$(sha256sum -- "${path}")"; then
    fail "could not calculate SHA-256: ${path}"
    return 1
  fi
  printf '%s\n' "${output%% *}"
}

verify_sha256() {
  local label="${1:?Artifact label is required}"
  local path="${2:?Artifact path is required}"
  local expected="${3:?Expected digest is required}"
  local actual
  if ! actual="$(sha256_file "${path}")"; then
    return 1
  fi
  test "${actual}" = "${expected}" || {
    # Do not disclose a digest for an unexpected path. The caller already has
    # the trusted expected value, while an untrusted uploader must not be able
    # to turn this root command into a file-hash oracle.
    fail "${label} SHA-256 mismatch"
    return 1
  }
}

require_exact_directory() {
  local label="${1:?Directory label is required}"
  local path="${2:?Directory path is required}"
  local expected_owner_group="${3:?Expected owner and group are required}"
  local expected_mode="${4:?Expected mode is required}"

  test -d "${path}" && test ! -L "${path}" || {
    fail "${label} is missing or is not a real directory: ${path}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${path}")" = "${expected_owner_group}" || {
    fail "${label} must be owned by ${expected_owner_group}"
    return 1
  }
  test "$(stat --format '%a' -- "${path}")" = "${expected_mode}" || {
    fail "${label} must have mode ${expected_mode}"
    return 1
  }
}

validate_fast_stage_infrastructure() {
  require_exact_directory 'fast stage root' "${FAST_STAGE_ROOT}" "root:${DEPLOY_GROUP}" '1730' || return $?
  require_exact_directory 'fast stage quarantine' "${FAST_STAGE_QUARANTINE}" 'root:root' '700' || return $?
  require_exact_directory 'verified stage root' "${DEPLOY_STATE_DIRECTORY}/staging" 'root:root' '700' || return $?
}

validate_quarantined_stage() {
  local expected_names
  local actual_names
  local name
  local path
  local owner

  case "${lane}" in
    WEB_ONLY)
      expected_names=$'deployment-plan.json\nrelease-manifest.json\nweb.zip'
      ;;
    BACKEND)
      expected_names=$'core-api.jar\ndeployment-plan.json\nrelease-manifest.json\nrun-migration-once.sh'
      ;;
    *)
      fail "cannot validate stage for lane ${lane}"
      return 1
      ;;
  esac

  actual_names="$(find -P "${quarantined_stage_root}" -mindepth 1 -maxdepth 1 -printf '%f\n' | LC_ALL=C sort)"
  test "${actual_names}" = "${expected_names}" || {
    fail 'quarantined stage does not contain the exact lane payload'
    return 1
  }

  while IFS= read -r name; do
    path="${quarantined_stage_root}/${name}"
    require_regular_readable_file "staged ${name}" "${path}" || return $?
    test "$(stat --format '%h' -- "${path}")" = '1' || {
      fail "staged ${name} must not have another hard link"
      return 1
    }
    owner="$(stat --format '%U:%G' -- "${path}")"
    case "${owner}" in
      "${DEPLOY_USER}:${DEPLOY_GROUP}"|'root:root') ;;
      *)
        fail "staged ${name} has an unexpected owner"
        return 1
        ;;
    esac
    if find "${path}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
      fail "staged ${name} must not be group- or world-writable"
      return 1
    fi
    chown --no-dereference root:root "${path}"
    chmod 0400 "${path}"
  done <<<"${expected_names}"
}

quarantine_stage_payload() {
  local requested_owner
  local quarantine_target

  validate_fast_stage_infrastructure || return $?
  requested_stage_root="${stage_root}"
  test "${requested_stage_root}" = "${FAST_STAGE_ROOT}/${release_id}" || {
    fail 'stage-root must exactly match the release id under the fixed fast stage root'
    return 1
  }
  test -d "${requested_stage_root}" && test ! -L "${requested_stage_root}" || {
    fail "stage root is missing or is not a real directory: ${requested_stage_root}"
    return 1
  }
  test "$(stat --format '%a' -- "${requested_stage_root}")" = '700' || {
    fail 'stage root must have mode 700'
    return 1
  }
  requested_owner="$(stat --format '%U:%G' -- "${requested_stage_root}")"
  if test -n "${SUDO_USER:-}"; then
    test "${SUDO_USER}" = "${DEPLOY_USER}" &&
      test "${requested_owner}" = "${DEPLOY_USER}:${DEPLOY_GROUP}" || {
      fail "stage root must be owned by the dedicated ${DEPLOY_USER} sudo caller"
      return 1
    }
  else
    case "${requested_owner}" in
      "${DEPLOY_USER}:${DEPLOY_GROUP}"|'root:root') ;;
      *)
        fail 'stage root has an unexpected owner'
        return 1
        ;;
    esac
  fi

  quarantine_target="${FAST_STAGE_QUARANTINE}/${release_id}.$$.stage"
  test ! -e "${quarantine_target}" && test ! -L "${quarantine_target}" || {
    fail 'quarantine target already exists'
    return 1
  }
  # Both paths are on the dedicated /tmp filesystem tree, so this rename is
  # atomic. Once moved below the root-only quarantine, the SSH account cannot
  # replace directory entries while root validates and snapshots them.
  mv --no-target-directory -- "${requested_stage_root}" "${quarantine_target}" || return $?
  quarantined_stage_root="${quarantine_target}"
  chown --no-dereference root:root "${quarantined_stage_root}"
  chmod 0700 "${quarantined_stage_root}"
  validate_quarantined_stage || return $?

  verified_stage_root="$(mktemp --directory --tmpdir="${DEPLOY_STATE_DIRECTORY}/staging" ".${release_id}.verified.XXXXXXXX")"
  chown root:root "${verified_stage_root}"
  chmod 0700 "${verified_stage_root}"
}

snapshot_stage_artifact() {
  local name="${1:?Artifact name is required}"
  local expected_sha="${2:?Expected artifact digest is required}"
  local expected_size="${3:?Expected artifact size is required}"
  local maximum_size="${4:?Maximum artifact size is required}"
  local source_path="${quarantined_stage_root}/${name}"
  local destination_path="${verified_stage_root}/${name}"
  local destination_pending="${destination_path}.pending"

  validate_sha256 "${name} SHA-256" "${expected_sha}" || return $?
  validate_artifact_size "${name}" "${expected_size}" "${maximum_size}" || return $?
  require_regular_readable_file "staged ${name}" "${source_path}" || return $?
  test "$(stat --format '%h' -- "${source_path}")" = '1' || {
    fail "staged ${name} must not have another hard link"
    return 1
  }
  test "$(stat --format '%s' -- "${source_path}")" = "${expected_size}" || {
    fail "staged ${name} size differs from the approved manifest"
    return 1
  }
  rm -f -- "${destination_pending}"
  # An uploader could retain an already-open writable descriptor across the
  # quarantine rename. count_bytes makes the privileged copy strictly bounded
  # while the exact size and hash checks reject every concurrent mutation.
  dd if="${source_path}" of="${destination_pending}" bs=1048576 \
    iflag=fullblock,count_bytes count="${expected_size}" status=none || return $?
  chown root:root "${destination_pending}"
  chmod 0400 "${destination_pending}"
  test "$(stat --format '%s' -- "${destination_pending}")" = "${expected_size}" || {
    fail "verified ${name} copy is incomplete"
    return 1
  }
  test "$(stat --format '%s' -- "${source_path}")" = "${expected_size}" || {
    fail "staged ${name} changed while it was copied"
    return 1
  }
  mv --no-target-directory -- "${destination_pending}" "${destination_path}"
  verify_sha256 "verified ${name}" "${destination_path}" "${expected_sha}" || return $?
}

cleanup_verified_stage_after_success() {
  local cleanup_status=0

  if test -n "${verified_stage_root}"; then
    remove_private_stage_tree "${verified_stage_root}" verified || cleanup_status=1
  fi
  if test -n "${quarantined_stage_root}"; then
    remove_private_stage_tree "${quarantined_stage_root}" quarantine || cleanup_status=1
  fi
  if test "${cleanup_status}" -eq 0; then
    verified_stage_root=''
    quarantined_stage_root=''
    requested_stage_root=''
  fi
  return "${cleanup_status}"
}

remove_private_stage_tree() {
  local path="${1:?Private stage path is required}"
  local kind="${2:?Private stage kind is required}"
  local resolved

  test -d "${path}" && test ! -L "${path}" || return 0
  test "$(stat --format '%U:%G' -- "${path}")" = 'root:root' || {
    fail "refusing to clean a private stage tree not owned by root:root: ${path}"
    return 1
  }
  resolved="$(realpath -- "${path}")" || return $?
  case "${kind}:${resolved}" in
    "verified:${DEPLOY_STATE_DIRECTORY}/staging/".*.verified.*|"quarantine:${FAST_STAGE_QUARANTINE}/"*.stage) ;;
    *)
      fail "refusing to clean an unexpected private stage path: ${resolved}"
      return 1
      ;;
  esac
  find -P "${resolved}" -xdev -depth -mindepth 1 -delete || return $?
  rmdir -- "${resolved}"
}

require_available_bytes() {
  local path="${1:?Filesystem path is required}"
  local required="${2:?Required byte count is required}"
  local available

  available="$(df --output=avail --block-size=1 -- "${path}" | sed -n '2p' | tr -d '[:space:]')"
  [[ "${available}" =~ ^[0-9]+$ ]] || {
    fail "could not determine available storage for ${path}"
    return 1
  }
  test "${available}" -ge "${required}" || {
    fail "insufficient free storage for immutable release under ${path}"
    return 1
  }
}

read_migration_identity() {
  local jar_path="${1:?JAR path is required}"
  require_regular_readable_file 'Core API JAR' "${jar_path}" || return $?

  python3 -I - "${jar_path}" <<'PY'
import hashlib
import re
import sys
import zipfile

jar_path = sys.argv[1]
candidate = re.compile(r"BOOT-INF/classes/db/migration/V.*[.]sql")
valid = re.compile(r"BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[^/]+[.]sql")

with zipfile.ZipFile(jar_path) as archive:
    names = sorted(name for name in archive.namelist() if candidate.fullmatch(name))
    if not names:
        raise SystemExit("Core API JAR has no numeric Flyway migrations.")
    if len(names) > 10_000:
        raise SystemExit("Core API JAR has too many Flyway migrations.")

    migrations = []
    versions = set()
    for name in names:
        match = valid.fullmatch(name)
        if match is None:
            raise SystemExit("Invalid Flyway migration name: " + name)
        version = int(match.group(1))
        if version in versions:
            raise SystemExit(f"Duplicate Flyway migration version: {version}")
        versions.add(version)
        info = archive.getinfo(name)
        if info.file_size > 33_554_432:
            raise SystemExit("Flyway migration exceeds the per-entry size limit: " + name)
        content_digest = hashlib.sha256(archive.read(info)).hexdigest()
        migrations.append((version, name, content_digest))

    if sum(archive.getinfo(name).file_size for name in names) > 134_217_728:
        raise SystemExit("Flyway migrations exceed the total size limit.")

state = "\n".join(
    f"{name}:{content_digest}"
    for _, name, content_digest in sorted(migrations, key=lambda item: item[1])
)
fingerprint = hashlib.sha256(state.encode("utf-8")).hexdigest()
print(f"{max(versions)} {fingerprint}")
PY
}

require_unchanged_migrations() {
  local candidate_version="${1:?Candidate migration version is required}"
  local candidate_fingerprint="${2:?Candidate migration fingerprint is required}"
  local current_version="${3:?Current migration version is required}"
  local current_fingerprint="${4:?Current migration fingerprint is required}"

  if test "${candidate_version}" != "${current_version}" ||
      test "${candidate_fingerprint}" != "${current_fingerprint}"; then
    printf 'FAST_LANE_FULL_REQUIRED reason=migrations_changed currentVersion=%s candidateVersion=%s algorithm=%s\n' \
      "${current_version}" "${candidate_version}" "${MIGRATION_FINGERPRINT_ALGORITHM}" >&2
    printf '{"event":"fast_lane_release","status":"blocked","lane":"BACKEND","reason":"migrations_changed","currentMigrationVersion":"%s","candidateMigrationVersion":"%s","fingerprintAlgorithm":"%s","next":"FULL"}\n' \
      "${current_version}" "${candidate_version}" "${MIGRATION_FINGERPRINT_ALGORITHM}" >&2
    return 4
  fi
}

safe_extract_web_bundle() {
  local archive_path="${1:?Web archive path is required}"
  local destination="${2:?Extraction destination is required}"

  require_regular_readable_file 'Web bundle' "${archive_path}" || return $?
  test -d "${destination}" || {
    fail "Web extraction destination does not exist: ${destination}"
    return 1
  }
  python3 -I - "${archive_path}" "${destination}" <<'PY'
import shutil
import stat
import sys
import zipfile
from pathlib import Path, PurePosixPath

archive_path = sys.argv[1]
root = Path(sys.argv[2])
max_entries = 20_000
max_total_size = 1_073_741_824

with zipfile.ZipFile(archive_path) as archive:
    entries = archive.infolist()
    if not entries or len(entries) > max_entries:
        raise SystemExit("Web bundle has an invalid entry count.")
    if sum(item.file_size for item in entries) > max_total_size:
        raise SystemExit("Web bundle exceeds the uncompressed size limit.")

    seen = set()
    for item in entries:
        name = item.filename
        path = PurePosixPath(name)
        if (
            not name
            or name.startswith("/")
            or "\\" in name
            or any(ord(character) < 32 or ord(character) == 127 for character in name)
            or any(part in ("", ".", "..") for part in path.parts)
        ):
            raise SystemExit("Unsafe web bundle path: " + name)
        if name in seen:
            raise SystemExit("Duplicate web bundle path: " + name)
        seen.add(name)
        if item.flag_bits & 0x1:
            raise SystemExit("Encrypted web bundle entries are not allowed.")

        unix_type = (item.external_attr >> 16) & 0o170000
        if unix_type not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise SystemExit("Non-regular web bundle entry is not allowed: " + name)

        target = root.joinpath(*path.parts)
        if item.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.open(item, "r") as source, target.open("xb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
PY
}

read_web_tree_fingerprint() {
  local tree_root="${1:?Web tree root is required}"

  test -d "${tree_root}" && test ! -L "${tree_root}" || {
    fail "Web tree is missing or unsafe: ${tree_root}"
    return 1
  }
  python3 -I - "${tree_root}" <<'PY'
import hashlib
import os
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
files = []
total_size = 0
for directory, directory_names, file_names in os.walk(root, topdown=True, followlinks=False):
    current = Path(directory)
    for name in directory_names:
        path = current / name
        mode = path.lstat().st_mode
        if not stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
            raise SystemExit("Web tree contains a non-directory or symlink node: " + str(path))
    for name in file_names:
        path = current / name
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
            raise SystemExit("Web tree contains a non-regular or symlink node: " + str(path))
        relative = path.relative_to(root).as_posix()
        if any(ord(character) < 32 or ord(character) == 127 for character in relative):
            raise SystemExit("Web tree contains an unsafe path.")
        total_size += metadata.st_size
        if total_size > 1_073_741_824:
            raise SystemExit("Web tree exceeds the total size limit.")
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        files.append((relative, metadata.st_size, digest))

if not files or len(files) > 20_000:
    raise SystemExit("Web tree has an invalid file count.")

# .NET StringComparer.Ordinal compares UTF-16 code units. Use the same ordering
# so the CI PowerShell manifest and the Linux verifier have one exact contract.
files.sort(key=lambda item: item[0].encode("utf-16-be", "surrogatepass"))
state = "\n".join(f"{name}:{size}:{digest}" for name, size, digest in files)
print(hashlib.sha256(state.encode("utf-8")).hexdigest())
PY
}

validate_current_link() {
  local link_path="${1:?Current link is required}"
  local allowed_root="${2:?Allowed release root is required}"
  local target

  test -L "${link_path}" || {
    fail "current path is not a symlink: ${link_path}"
    return 1
  }
  if ! target="$(readlink -f -- "${link_path}")"; then
    fail "current release link cannot be resolved: ${link_path}"
    return 1
  fi
  test -d "${target}" || {
    fail "current release target does not exist: ${target}"
    return 1
  }
  case "${target}" in
    "${allowed_root}"/*) ;;
    *)
      fail "current release target escapes ${allowed_root}: ${target}"
      return 1
      ;;
  esac
  printf '%s\n' "${target}"
}

switch_link_atomically() {
  local target="${1:?Target is required}"
  local link_path="${2:?Link path is required}"

  pending_link="${link_path}.next.$$"
  test ! -e "${pending_link}" && test ! -L "${pending_link}" || {
    fail "temporary link already exists: ${pending_link}"
    return 1
  }
  ln -s -- "${target}" "${pending_link}" || return $?
  mv --force --no-target-directory -- "${pending_link}" "${link_path}" || return $?
  pending_link=''
}

wait_for_core_release() {
  local expected_release="${1:?Expected backend release is required}"
  local max_attempts="${2:-60}"
  local attempt
  local main_pid
  local running_release

  [[ "${max_attempts}" =~ ^[1-9][0-9]*$ ]] || {
    fail 'health-check attempts must be a positive integer'
    return 1
  }
  for attempt in $(seq 1 "${max_attempts}"); do
    if systemctl is-active --quiet "${CORE_SERVICE}" &&
        curl --fail --silent --show-error --max-time 3 "${CORE_HEALTH_URL}" >/dev/null; then
      main_pid="$(systemctl show "${CORE_SERVICE}" --property MainPID --value)"
      if [[ "${main_pid}" =~ ^[1-9][0-9]*$ ]] &&
          running_release="$(readlink -f -- "/proc/${main_pid}/cwd" 2>/dev/null)" &&
          test "${running_release}" = "${expected_release}"; then
        return 0
      fi
    fi
    sleep 2
  done
  fail "Core API did not become healthy from ${expected_release}"
}

verify_web_is_served() {
  local expected_index_sha="${1:?Expected index digest is required}"
  health_response_file="$(mktemp /tmp/hotel-ai-os-fast-web-health.XXXXXXXX)"
  curl --fail --silent --show-error --max-time 15 \
    --resolve 'www.sfgzt.cn:443:127.0.0.1' \
    --header 'Accept-Encoding: identity' \
    --header 'Cache-Control: no-cache' \
    --output "${health_response_file}" \
    "${WEB_HEALTH_URL}" || return $?
  verify_sha256 'served index.html' "${health_response_file}" "${expected_index_sha}" || return $?
  rm -f -- "${health_response_file}"
  health_response_file=''
}

cleanup_incomplete_state() {
  set +e
  if test -n "${pending_link}"; then
    case "${pending_link}" in
      "${BACKEND_CURRENT_LINK}.next."*|"${WEB_CURRENT_LINK}.next."*) rm -f -- "${pending_link}" ;;
    esac
  fi
  if test -n "${health_response_file}"; then
    case "${health_response_file}" in
      /tmp/hotel-ai-os-fast-web-health.*) rm -f -- "${health_response_file}" ;;
    esac
  fi
  if test -n "${pending_state_file}"; then
    case "${pending_state_file}" in
      "${DEPLOY_STATE_DIRECTORY}"/.deployed-source-commit.*|"${DEPLOY_STATE_DIRECTORY}"/.full-release-in-progress.*)
        rm -f -- "${pending_state_file}"
        ;;
    esac
  fi
  if test -n "${staging_directory}" && test -d "${staging_directory}"; then
    case "${staging_directory}" in
      "${BACKEND_RELEASE_ROOT}"/.*.installing.*|"${WEB_RELEASE_ROOT}"/.*.installing.*)
        rm -rf --one-file-system -- "${staging_directory}"
        ;;
    esac
  fi
  cleanup_verified_stage_after_success || true
}

failure_handler() {
  local status="${1:-1}"
  local link_restored=true
  local health_verified=true
  local marker_restored=true
  local rollback_ok=true

  trap - ERR INT TERM EXIT
  set +e
  if test "${link_switched}" = true; then
    if test -n "${pending_link}"; then
      case "${pending_link}" in
        "${BACKEND_CURRENT_LINK}.next."*|"${WEB_CURRENT_LINK}.next."*) rm -f -- "${pending_link}" ;;
      esac
      pending_link=''
    fi
    switch_link_atomically "${previous_target}" "${current_link}" || link_restored=false
    if test "${link_restored}" = true; then
      if test "${lane}" = 'BACKEND'; then
        systemctl restart "${CORE_SERVICE}" || health_verified=false
        wait_for_core_release "${previous_target}" || health_verified=false
      elif test "${lane}" = 'WEB_ONLY'; then
        require_regular_readable_file 'previous Web index' "${previous_target}/index.html" || health_verified=false
        if test -n "${previous_web_index_sha}"; then
          verify_sha256 'previous Web index' "${previous_target}/index.html" "${previous_web_index_sha}" || health_verified=false
          verify_web_is_served "${previous_web_index_sha}" || health_verified=false
        else
          health_verified=false
        fi
      fi
    else
      health_verified=false
    fi
  fi
  if test "${source_commit_updated}" = true; then
    if test "${link_restored}" = true; then
      write_deployed_source_commit "${expected_source_commit}" || marker_restored=false
    else
      # The live target is uncertain, so no ordinary commit marker is safe.
      marker_restored=false
    fi
  fi
  if test "${link_restored}" != true; then
    marker_restored=false
  fi
  if test "${marker_restored}" != true; then
    mark_deploy_state_inconsistent 'rollback_marker_restore_failed' || true
  fi
  if test "${link_restored}" != true || test "${health_verified}" != true || test "${marker_restored}" != true; then
    rollback_ok=false
  fi
  cleanup_incomplete_state
  if test "${rollback_ok}" = true; then
    printf 'FAST_LANE_RELEASE_ROLLED_BACK lane=%s releaseId=%s previous=%s\n' \
      "${lane}" "${release_id}" "${previous_target}" >&2
  else
    printf 'FAST_LANE_RELEASE_ROLLBACK_FAILED lane=%s releaseId=%s previous=%s\n' \
      "${lane}" "${release_id}" "${previous_target}" >&2
  fi
  printf '{"event":"fast_lane_release","status":"failed","lane":"%s","releaseId":"%s","rollbackSucceeded":%s}\n' \
    "${lane}" "${release_id}" "${rollback_ok}" >&2
  test "${status}" -ne 0 || status=1
  exit "${status}"
}

acquire_release_lock() {
  exec 9>"${RELEASE_LOCK}"
  if ! flock --nonblock 9; then
    printf '%s\n' 'FAST_LANE_RELEASE_BUSY another release is already in progress' >&2
    return 5
  fi
}

require_installed_root_command() {
  local installed_command

  test "$(id -u)" -eq 0 || {
    fail 'fast-lane installation must run as root'
    return 1
  }
  installed_command="$(readlink -f -- "$0")" || return $?
  test "${installed_command}" = "${INSTALLED_COMMAND}" || {
    fail "fast-lane installation must use the preinstalled command: ${INSTALLED_COMMAND}"
    return 1
  }
  test -f "${INSTALLED_COMMAND}" && test ! -L "${INSTALLED_COMMAND}" || {
    fail 'preinstalled fast-lane command must be a regular non-symlink file'
    return 1
  }
  test "$(stat --format '%U:%G' -- "${INSTALLED_COMMAND}")" = 'root:root' || {
    fail 'preinstalled fast-lane command must be owned by root:root'
    return 1
  }
  if find "${INSTALLED_COMMAND}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail 'preinstalled fast-lane command must not be group- or world-writable'
    return 1
  fi
}

prepare_common_release_state() {
  local deployed_source_commit

  require_installed_root_command || return $?
  validate_release_id "${release_id}" || return $?
  validate_source_commit 'PILOT_EXPECTED_SOURCE_COMMIT' "${expected_source_commit}" || return $?
  validate_source_commit 'PILOT_SOURCE_COMMIT' "${source_commit}" || return $?
  test "${source_commit}" != "${expected_source_commit}" || {
    fail 'PILOT_SOURCE_COMMIT must advance beyond PILOT_EXPECTED_SOURCE_COMMIT'
    return 1
  }
  validate_absolute_path 'stage-root' "${stage_root}" || return $?
  acquire_release_lock || return $?
  deployed_source_commit="$(read_deployed_source_commit)" || return $?
  if test "${deployed_source_commit}" != "${expected_source_commit}"; then
    printf 'FAST_LANE_STALE_BASE expected=%s actual=%s\n' \
      "${expected_source_commit}" "${deployed_source_commit}" >&2
    return 6
  fi
  trap cleanup_incomplete_state EXIT
  quarantine_stage_payload || return $?
}

verify_installed_release() {
  local verify_lane="${1:?Verification lane is required}"
  local expected_commit="${2:?Source commit is required}"
  local expected_hash="${3:?Artifact hash is required}"
  local expected_tree_hash="${4-}"
  local deployed_commit
  local target

  require_installed_root_command || return $?
  validate_source_commit 'source commit' "${expected_commit}" || return $?
  validate_sha256 'verification hash' "${expected_hash}" || return $?
  acquire_release_lock || return $?
  deployed_commit="$(read_deployed_source_commit)" || return $?
  test "${deployed_commit}" = "${expected_commit}" || {
    fail "deployed source commit differs from verification target: expected=${expected_commit} actual=${deployed_commit}"
    return 1
  }

  case "${verify_lane}" in
    WEB_ONLY)
      validate_sha256 'Web tree verification hash' "${expected_tree_hash}" || return $?
      target="$(validate_current_link "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
      require_regular_readable_file 'active Web index' "${target}/index.html" || return $?
      verify_sha256 'active Web index' "${target}/index.html" "${expected_hash}" || return $?
      test "$(read_web_tree_fingerprint "${target}")" = "${expected_tree_hash}" || {
        fail 'active Web tree fingerprint differs from the selected artifact'
        return 1
      }
      verify_web_is_served "${expected_hash}" || return $?
      ;;
    BACKEND)
      target="$(validate_current_link "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
      require_regular_readable_file 'active Core API JAR' "${target}/core-api.jar" || return $?
      verify_sha256 'active Core API JAR' "${target}/core-api.jar" "${expected_hash}" || return $?
      wait_for_core_release "${target}" || return $?
      ;;
    *)
      fail "unsupported verification lane: ${verify_lane}"
      return 2
      ;;
  esac

  printf 'FAST_LANE_RELEASE_VERIFIED lane=%s sourceCommit=%s current=%s\n' \
    "${verify_lane}" "${expected_commit}" "${target}"
  printf '{"event":"fast_lane_release_verify","status":"success","lane":"%s","sourceCommit":"%s","current":"%s"}\n' \
    "${verify_lane}" "${expected_commit}" "${target}"
}

show_deployed_source_commit() {
  local deployed_commit

  require_installed_root_command || return $?
  acquire_release_lock || return $?
  deployed_commit="$(read_deployed_source_commit)" || return $?
  printf '%s\n' "${deployed_commit}"
}

show_installer_policy() {
  local installer_sha

  require_installed_root_command || return $?
  acquire_release_lock || return $?
  installer_sha="$(sha256_file "${INSTALLED_COMMAND}")" || return $?
  validate_sha256 'installed fast-lane policy SHA-256' "${installer_sha}" || return $?
  printf 'FAST_LANE_POLICY version=%s sha256=%s\n' \
    "${INSTALLER_POLICY_VERSION}" "${installer_sha}"
}

mark_verified_full_release() {
  local baseline_backend
  local baseline_web
  local verified_commit="${1:?Verified source commit is required}"
  local verified_release_id="${2:?Verified release id is required}"
  local backend_target
  local full_record
  local full_release_id
  local full_target
  local web_target

  require_installed_root_command || return $?
  test "${SUDO_USER:-}" != "${DEPLOY_USER}" || {
    fail 'the dedicated fast deployer may not mark a FULL release'
    return 1
  }
  validate_source_commit 'verified FULL source commit' "${verified_commit}" || return $?
  validate_release_id "${verified_release_id}" || return $?
  acquire_release_lock || return $?
  validate_deploy_state_directory || return $?
  test ! -e "${DEPLOY_STATE_INCONSISTENT_FILE}" && test ! -L "${DEPLOY_STATE_INCONSISTENT_FILE}" || {
    fail 'deploy state is inconsistent; repair it before finalizing a complete release'
    return 1
  }
  full_record="$(read_full_release_in_progress)" || return $?
  read -r full_target full_release_id baseline_backend baseline_web <<<"${full_record}"
  test "${full_target}" = "${verified_commit}" || {
    fail "complete release target differs from verified commit: expected=${full_target} actual=${verified_commit}"
    return 1
  }
  test "${full_release_id}" = "${verified_release_id}" || {
    fail "complete release id differs from verified release: expected=${full_release_id} actual=${verified_release_id}"
    return 1
  }
  backend_target="$(validate_current_link "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
  web_target="$(validate_current_link "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
  require_regular_readable_file 'FULL release Web index' "${web_target}/index.html" || return $?
  require_regular_readable_file 'FULL release Core API JAR' "${backend_target}/core-api.jar" || return $?
  require_regular_readable_file 'FULL release backend source identity' "${backend_target}/.source-commit" || return $?
  require_regular_readable_file 'FULL release Web source identity' "${web_target}/.source-commit" || return $?
  require_regular_readable_file 'FULL release backend operation identity' "${backend_target}/.release-id" || return $?
  require_regular_readable_file 'FULL release Web operation identity' "${web_target}/.release-id" || return $?
  test "$(cat -- "${backend_target}/.source-commit")" = "${verified_commit}" || {
    fail 'FULL release backend source identity differs from the verified commit'
    return 1
  }
  test "$(cat -- "${web_target}/.source-commit")" = "${verified_commit}" || {
    fail 'FULL release Web source identity differs from the verified commit'
    return 1
  }
  test "$(cat -- "${backend_target}/.release-id")" = "${verified_release_id}" || {
    fail 'FULL release backend operation identity differs from the verified release id'
    return 1
  }
  test "$(cat -- "${web_target}/.release-id")" = "${verified_release_id}" || {
    fail 'FULL release Web operation identity differs from the verified release id'
    return 1
  }
  wait_for_core_release "${backend_target}" || return $?
  curl --fail --silent --show-error --max-time 15 \
    --resolve 'www.sfgzt.cn:443:127.0.0.1' \
    --header 'Accept-Encoding: identity' \
    --header 'Cache-Control: no-cache' \
    --output /dev/null \
    "${WEB_HEALTH_URL}" || return $?
  write_deployed_source_commit "${verified_commit}" || return $?
  rm -f -- "${DEPLOY_STATE_INCONSISTENT_FILE}"
  rm -f -- "${FULL_RELEASE_IN_PROGRESS_FILE}"
  printf 'FAST_LANE_FULL_BASELINE_MARKED sourceCommit=%s backend=%s web=%s\n' \
    "${verified_commit}" "${backend_target}" "${web_target}"
}

begin_full_release() {
  local backend_target
  local target_commit="${1:?Target source commit is required}"
  local target_release_id="${2:?Release id is required}"
  local deployed_commit
  local existing_record
  local web_target

  require_installed_root_command || return $?
  test "${SUDO_USER:-}" != "${DEPLOY_USER}" || {
    fail 'the dedicated fast deployer may not begin a FULL release'
    return 1
  }
  validate_source_commit 'FULL target source commit' "${target_commit}" || return $?
  validate_release_id "${target_release_id}" || return $?
  acquire_release_lock || return $?
  validate_deploy_state_directory || return $?
  test ! -e "${DEPLOY_STATE_INCONSISTENT_FILE}" && test ! -L "${DEPLOY_STATE_INCONSISTENT_FILE}" || {
    fail 'deploy state is inconsistent; repair it before beginning a complete release'
    return 1
  }

  if test -e "${FULL_RELEASE_IN_PROGRESS_FILE}" || test -L "${FULL_RELEASE_IN_PROGRESS_FILE}"; then
    existing_record="$(read_full_release_in_progress)" || return $?
    fail "another complete release is already in progress: ${existing_record}"
    return 1
  fi

  deployed_commit="$(read_deployed_source_commit)" || return $?
  test "${deployed_commit}" != "${target_commit}" || {
    fail 'FULL target commit is already recorded as deployed'
    return 1
  }
  backend_target="$(validate_current_link "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
  web_target="$(validate_current_link "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
  trap cleanup_incomplete_state EXIT
  write_full_release_in_progress "${target_commit}" "${target_release_id}" "${backend_target}" "${web_target}"
  trap - EXIT
  printf 'FULL_RELEASE_BEGUN sourceCommit=%s releaseId=%s previousSourceCommit=%s\n' \
    "${target_commit}" "${target_release_id}" "${deployed_commit}"
}

resume_full_release() {
  local target_commit="${1:?Target source commit is required}"
  local target_release_id="${2:?Release id is required}"
  local backend_target
  local full_record
  local marked_commit
  local marked_release
  local previous_backend
  local previous_web
  local web_target

  require_installed_root_command || return $?
  test "${SUDO_USER:-}" != "${DEPLOY_USER}" || {
    fail 'the dedicated fast deployer may not resume a FULL release'
    return 1
  }
  validate_source_commit 'FULL target source commit' "${target_commit}" || return $?
  validate_release_id "${target_release_id}" || return $?
  acquire_release_lock || return $?
  full_record="$(read_full_release_in_progress)" || return $?
  read -r marked_commit marked_release previous_backend previous_web <<<"${full_record}"
  test "${marked_commit}" = "${target_commit}" && test "${marked_release}" = "${target_release_id}" || {
    fail "complete release marker differs from the requested recovery operation"
    return 1
  }
  backend_target="$(validate_current_link "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
  web_target="$(validate_current_link "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
  printf 'FULL_RELEASE_RESUME_REQUIRED sourceCommit=%s releaseId=%s previousBackend=%s previousWeb=%s backend=%s web=%s next=complete-install-start-smoke-final-audit\n' \
    "${target_commit}" "${target_release_id}" "${previous_backend}" "${previous_web}" "${backend_target}" "${web_target}"
}

abort_full_release() {
  local target_commit="${1:?Target source commit is required}"
  local target_release_id="${2:?Release id is required}"
  local backend_target
  local full_record
  local marked_commit
  local marked_release
  local previous_backend
  local previous_web
  local previous_web_index_sha
  local web_target

  require_installed_root_command || return $?
  test "${SUDO_USER:-}" != "${DEPLOY_USER}" || {
    fail 'the dedicated fast deployer may not abort a FULL release'
    return 1
  }
  validate_source_commit 'FULL target source commit' "${target_commit}" || return $?
  validate_release_id "${target_release_id}" || return $?
  acquire_release_lock || return $?
  validate_deploy_state_directory || return $?
  test ! -e "${DEPLOY_STATE_INCONSISTENT_FILE}" && test ! -L "${DEPLOY_STATE_INCONSISTENT_FILE}" || {
    fail 'deploy state is inconsistent; a FULL release cannot be aborted automatically'
    return 1
  }
  full_record="$(read_full_release_in_progress)" || return $?
  read -r marked_commit marked_release previous_backend previous_web <<<"${full_record}"
  test "${marked_commit}" = "${target_commit}" && test "${marked_release}" = "${target_release_id}" || {
    fail 'complete release marker differs from the requested abort operation'
    return 1
  }
  backend_target="$(validate_current_link "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
  web_target="$(validate_current_link "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
  test "${backend_target}" = "${previous_backend}" && test "${web_target}" = "${previous_web}" || {
    fail 'a live release link has changed; resume and finish or repair the FULL release instead of aborting'
    return 1
  }
  wait_for_core_release "${backend_target}" 1 || return $?
  require_regular_readable_file 'active Web index' "${web_target}/index.html" || return $?
  previous_web_index_sha="$(sha256_file "${web_target}/index.html")" || return $?
  verify_web_is_served "${previous_web_index_sha}" || return $?
  rm -f -- "${FULL_RELEASE_IN_PROGRESS_FILE}"
  printf 'FULL_RELEASE_ABORTED sourceCommit=%s releaseId=%s preservedBackend=%s preservedWeb=%s\n' \
    "${target_commit}" "${target_release_id}" "${backend_target}" "${web_target}"
}

install_web_only() {
  local expected_bundle_sha="${1:?Expected web bundle digest is required}"
  local expected_bundle_size="${2:?Expected web bundle size is required}"
  local expected_index_sha="${3:?Expected index digest is required}"
  local expected_tree_sha="${4:?Expected Web tree digest is required}"
  local archive_path
  local final_release
  local actual_tree_sha

  validate_sha256 'web ZIP SHA-256' "${expected_bundle_sha}"
  validate_artifact_size 'web ZIP' "${expected_bundle_size}" 1073741824
  validate_sha256 'index SHA-256' "${expected_index_sha}"
  validate_sha256 'Web tree SHA-256' "${expected_tree_sha}"
  prepare_common_release_state
  require_available_bytes "${DEPLOY_STATE_DIRECTORY}/staging" "$((expected_bundle_size + 67108864))"
  snapshot_stage_artifact 'web.zip' "${expected_bundle_sha}" "${expected_bundle_size}" 1073741824
  stage_root="${verified_stage_root}"
  archive_path="${stage_root}/web.zip"
  require_regular_readable_file 'Web bundle' "${archive_path}"
  verify_sha256 'web bundle' "${archive_path}" "${expected_bundle_sha}"

  release_root="${WEB_RELEASE_ROOT}"
  current_link="${WEB_CURRENT_LINK}"
  previous_target="$(validate_current_link "${current_link}" "${release_root}")"
  require_regular_readable_file 'previous Web index' "${previous_target}/index.html"
  previous_web_index_sha="$(sha256_file "${previous_target}/index.html")"
  validate_sha256 'previous Web index SHA-256' "${previous_web_index_sha}"
  require_available_bytes "${release_root}" 2147483648
  final_release="${release_root}/${release_id}"
  test ! -e "${final_release}" && test ! -L "${final_release}" ||
    fail "web release already exists: ${final_release}"

  install -d -o root -g root -m 0755 "${release_root}"
  staging_directory="$(mktemp --directory --tmpdir="${release_root}" ".${release_id}.installing.XXXXXXXX")"
  safe_extract_web_bundle "${archive_path}" "${staging_directory}"
  require_regular_readable_file 'Web index' "${staging_directory}/index.html"
  verify_sha256 'web index' "${staging_directory}/index.html" "${expected_index_sha}"
  actual_tree_sha="$(read_web_tree_fingerprint "${staging_directory}")"
  test "${actual_tree_sha}" = "${expected_tree_sha}" || fail 'extracted Web tree fingerprint mismatch'
  chown -R root:root "${staging_directory}"
  find "${staging_directory}" -type f -exec chmod 0444 {} +
  find "${staging_directory}" -type d -exec chmod 0555 {} +
  mv --no-target-directory -- "${staging_directory}" "${final_release}"
  staging_directory=''

  trap 'failure_handler $?' ERR
  trap 'failure_handler 130' INT
  trap 'failure_handler 143' TERM
  link_switched=true
  switch_link_atomically "${final_release}" "${current_link}"
  verify_web_is_served "${expected_index_sha}"
  test "$(read_web_tree_fingerprint "${final_release}")" = "${expected_tree_sha}" ||
    fail 'active Web tree fingerprint mismatch'
  # Arm marker restoration before the atomic marker write so a signal in the
  # tiny write/assignment window cannot leave the link and marker inconsistent.
  source_commit_updated=true
  write_deployed_source_commit "${source_commit}"
  trap - ERR INT TERM
  link_switched=false
  cleanup_verified_stage_after_success ||
    printf '%s\n' 'FAST_LANE_RELEASE_WARNING verified staging cleanup was incomplete' >&2

  printf 'FAST_LANE_RELEASE_COMPLETE lane=WEB_ONLY releaseId=%s previous=%s current=%s\n' \
    "${release_id}" "${previous_target}" "${final_release}"
  printf '{"event":"fast_lane_release","status":"success","lane":"WEB_ONLY","releaseId":"%s","sourceCommit":"%s","previousSourceCommit":"%s","previous":"%s","current":"%s","webBundleSha256":"%s","indexSha256":"%s","webTreeSha256":"%s","webTreeFingerprintAlgorithm":"%s"}\n' \
    "${release_id}" "${source_commit}" "${expected_source_commit}" "${previous_target}" "${final_release}" "${expected_bundle_sha}" "${expected_index_sha}" "${expected_tree_sha}" "${WEB_TREE_FINGERPRINT_ALGORITHM}"
}

install_backend_only() {
  local expected_jar_sha="${1:?Expected JAR digest is required}"
  local expected_jar_size="${2:?Expected JAR size is required}"
  local expected_runner_sha="${3:?Expected migration runner digest is required}"
  local expected_runner_size="${4:?Expected migration runner size is required}"
  local expected_migration_version="${5:?Expected migration version is required}"
  local expected_migration_fingerprint="${6:?Expected migration fingerprint is required}"
  local jar_path
  local runner_path
  local final_release
  local candidate_identity
  local candidate_version
  local candidate_fingerprint
  local current_identity
  local current_version
  local current_fingerprint

  validate_sha256 'JAR SHA-256' "${expected_jar_sha}"
  validate_artifact_size 'Core API JAR' "${expected_jar_size}" 1073741824
  validate_sha256 'migration runner SHA-256' "${expected_runner_sha}"
  validate_artifact_size 'migration runner' "${expected_runner_size}" 16777216
  validate_migration_version "${expected_migration_version}"
  validate_sha256 'migration fingerprint' "${expected_migration_fingerprint}"
  prepare_common_release_state
  require_available_bytes "${DEPLOY_STATE_DIRECTORY}/staging" "$((expected_jar_size + expected_runner_size + 67108864))"
  snapshot_stage_artifact 'core-api.jar' "${expected_jar_sha}" "${expected_jar_size}" 1073741824
  snapshot_stage_artifact 'run-migration-once.sh' "${expected_runner_sha}" "${expected_runner_size}" 16777216
  stage_root="${verified_stage_root}"
  jar_path="${stage_root}/core-api.jar"
  runner_path="${stage_root}/run-migration-once.sh"
  require_regular_readable_file 'Core API JAR' "${jar_path}"
  require_regular_readable_file 'migration runner' "${runner_path}"
  verify_sha256 'Core API JAR' "${jar_path}" "${expected_jar_sha}"
  verify_sha256 'migration runner' "${runner_path}" "${expected_runner_sha}"

  release_root="${BACKEND_RELEASE_ROOT}"
  current_link="${BACKEND_CURRENT_LINK}"
  previous_target="$(validate_current_link "${current_link}" "${release_root}")"
  require_regular_readable_file 'current Core API JAR' "${previous_target}/core-api.jar"
  require_available_bytes "${release_root}" 1073741824

  candidate_identity="$(read_migration_identity "${jar_path}")"
  read -r candidate_version candidate_fingerprint <<<"${candidate_identity}"
  test "${candidate_version}" = "${expected_migration_version}" ||
    fail "candidate migration version differs from verified input: expected=${expected_migration_version} actual=${candidate_version}"
  test "${candidate_fingerprint}" = "${expected_migration_fingerprint}" ||
    fail "candidate migration fingerprint differs from verified input"

  current_identity="$(read_migration_identity "${previous_target}/core-api.jar")"
  read -r current_version current_fingerprint <<<"${current_identity}"
  require_unchanged_migrations \
    "${candidate_version}" "${candidate_fingerprint}" \
    "${current_version}" "${current_fingerprint}"

  # A BACKEND fast release is only valid while the currently linked service is
  # healthy and actually running from that link. Exact migration identity means
  # there is nothing to migrate, so this path deliberately never starts Flyway.
  wait_for_core_release "${previous_target}" 1

  final_release="${release_root}/${release_id}"
  test ! -e "${final_release}" && test ! -L "${final_release}" ||
    fail "backend release already exists: ${final_release}"
  install -d -o root -g root -m 0755 "${release_root}"
  staging_directory="$(mktemp --directory --tmpdir="${release_root}" ".${release_id}.installing.XXXXXXXX")"
  install -o root -g root -m 0444 "${jar_path}" "${staging_directory}/core-api.jar"
  install -o root -g root -m 0555 "${runner_path}" "${staging_directory}/run-migration-once.sh"
  chmod 0555 "${staging_directory}"
  verify_sha256 'installed Core API JAR' "${staging_directory}/core-api.jar" "${expected_jar_sha}"
  verify_sha256 'installed migration runner' "${staging_directory}/run-migration-once.sh" "${expected_runner_sha}"
  mv --no-target-directory -- "${staging_directory}" "${final_release}"
  staging_directory=''

  trap 'failure_handler $?' ERR
  trap 'failure_handler 130' INT
  trap 'failure_handler 143' TERM
  link_switched=true
  switch_link_atomically "${final_release}" "${current_link}"
  systemctl restart "${CORE_SERVICE}"
  wait_for_core_release "${final_release}"
  verify_sha256 'active Core API JAR' "${current_link}/core-api.jar" "${expected_jar_sha}"
  source_commit_updated=true
  write_deployed_source_commit "${source_commit}"
  trap - ERR INT TERM
  link_switched=false
  cleanup_verified_stage_after_success ||
    printf '%s\n' 'FAST_LANE_RELEASE_WARNING verified staging cleanup was incomplete' >&2

  printf 'FAST_LANE_RELEASE_COMPLETE lane=BACKEND releaseId=%s previous=%s current=%s\n' \
    "${release_id}" "${previous_target}" "${final_release}"
  printf '{"event":"fast_lane_release","status":"success","lane":"BACKEND","releaseId":"%s","sourceCommit":"%s","previousSourceCommit":"%s","previous":"%s","current":"%s","jarSha256":"%s","migrationVersion":"%s","migrationFingerprint":"%s","fingerprintAlgorithm":"%s"}\n' \
    "${release_id}" "${source_commit}" "${expected_source_commit}" "${previous_target}" "${final_release}" "${expected_jar_sha}" \
    "${candidate_version}" "${candidate_fingerprint}" "${MIGRATION_FINGERPRINT_ALGORITHM}"
}

inspect_jar() {
  local jar_path="${1:?Absolute JAR path is required}"
  local identity
  local migration_version
  local migration_fingerprint
  local jar_sha

  validate_absolute_path 'JAR path' "${jar_path}"
  require_regular_readable_file 'Core API JAR' "${jar_path}"
  identity="$(read_migration_identity "${jar_path}")"
  read -r migration_version migration_fingerprint <<<"${identity}"
  jar_sha="$(sha256_file "${jar_path}")"
  printf 'FAST_LANE_MIGRATION_INSPECT jarSha256=%s migrationVersion=%s migrationFingerprint=%s fingerprintAlgorithm=%s\n' \
    "${jar_sha}" "${migration_version}" "${migration_fingerprint}" "${MIGRATION_FINGERPRINT_ALGORITHM}"
  printf '{"event":"fast_lane_migration_inspect","jarSha256":"%s","migrationVersion":"%s","migrationFingerprint":"%s","fingerprintAlgorithm":"%s"}\n' \
    "${jar_sha}" "${migration_version}" "${migration_fingerprint}" "${MIGRATION_FINGERPRINT_ALGORITHM}"
}

main() {
  if test "$#" -lt 1; then
    usage
    exit 2
  fi
  lane="$1"
  shift

  case "${lane}" in
    INSPECT)
      test "$#" -eq 1 || { usage; exit 2; }
      inspect_jar "$1"
      ;;
    POLICY)
      test "$#" -eq 0 || { usage; exit 2; }
      show_installer_policy
      ;;
    STATUS)
      test "$#" -eq 0 || { usage; exit 2; }
      show_deployed_source_commit
      ;;
    BEGIN_FULL)
      test "$#" -eq 2 || { usage; exit 2; }
      begin_full_release "$1" "$2"
      ;;
    RESUME_FULL)
      test "$#" -eq 2 || { usage; exit 2; }
      resume_full_release "$1" "$2"
      ;;
    ABORT_FULL)
      test "$#" -eq 2 || { usage; exit 2; }
      abort_full_release "$1" "$2"
      ;;
    MARK_FULL)
      test "$#" -eq 2 || { usage; exit 2; }
      mark_verified_full_release "$1" "$2"
      ;;
    VERIFY)
      if test "${1-}" = 'WEB_ONLY'; then
        test "$#" -eq 4 || { usage; exit 2; }
        verify_installed_release "$1" "$2" "$3" "$4"
      elif test "${1-}" = 'BACKEND'; then
        test "$#" -eq 3 || { usage; exit 2; }
        verify_installed_release "$1" "$2" "$3"
      else
        usage
        exit 2
      fi
      ;;
    WEB_ONLY)
      test "$#" -eq 8 || { usage; exit 2; }
      release_id="$1"
      stage_root="$2"
      expected_source_commit="$3"
      source_commit="$4"
      install_web_only "$5" "$6" "$7" "$8"
      ;;
    BACKEND)
      test "$#" -eq 10 || { usage; exit 2; }
      release_id="$1"
      stage_root="$2"
      expected_source_commit="$3"
      source_commit="$4"
      install_backend_only "$5" "$6" "$7" "$8" "$9" "${10}"
      ;;
    FULL)
      test "$#" -eq 0 || { usage; exit 2; }
      printf '%s\n' 'FAST_LANE_FULL_BLOCKED Use the approved full deployment workflow: backup and restore verification, migration validation, 60-install-release.sh, service start, smoke tests, and final audit.' >&2
      printf '%s\n' '{"event":"fast_lane_release","status":"blocked","lane":"FULL","next":"approved_full_deployment_workflow"}' >&2
      exit 3
      ;;
    *)
      usage
      exit 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
