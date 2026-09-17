#!/bin/bash
set -Eeuo pipefail
shopt -s inherit_errexit
umask 077

PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
export PATH
readonly PATH

readonly FAST_RELEASE_COMMAND='/usr/local/sbin/hotel-ai-os-install-fast-lane-release'
readonly FULL_RELEASE_IN_PROGRESS_FILE='/var/lib/hotel-ai-os-deploy/FULL_RELEASE_IN_PROGRESS'
readonly DEPLOY_STATE_DIRECTORY='/var/lib/hotel-ai-os-deploy'
readonly BACKEND_RELEASE_ROOT='/opt/hotel-ai-os/releases'
readonly WEB_RELEASE_ROOT='/srv/www/hotel-ai-os/releases'
readonly BACKEND_CURRENT_LINK='/opt/hotel-ai-os/current'
readonly WEB_CURRENT_LINK='/srv/www/hotel-ai-os/current'
readonly WEB_HEALTH_URL='https://www.sfgzt.cn/index.html'
readonly WEB_TREE_FINGERPRINT_ALGORITHM='sha256(sorted-path-colon-size-colon-content-sha256-v1)'
readonly FULL_RELEASE_MARKER_MAX_BYTES=1024

fail() {
  printf '%s\n' "$*" >&2
  return 1
}

validate_source_commit() {
  local value="${1-}"
  [[ "${value}" =~ ^[0-9a-f]{40}$ ]] ||
    fail 'Verified FULL source commit must be a lowercase 40-character Git SHA.'
}

validate_release_id() {
  local value="${1-}"
  [[ "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] &&
    [[ "${value}" != *'..'* ]] ||
    fail 'Verified FULL release id is invalid.'
}

validate_sha256() {
  local label="${1:?SHA label is required}"
  local value="${2-}"
  [[ "${value}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "${label} must be a lowercase SHA-256 digest."
}

validate_full_marker_size() {
  local byte_count="${1-}"
  [[ "${byte_count}" =~ ^[1-9][0-9]*$ ]] &&
    test "${byte_count}" -le "${FULL_RELEASE_MARKER_MAX_BYTES}" ||
    fail "FULL release marker must be 1-${FULL_RELEASE_MARKER_MAX_BYTES} bytes."
}

require_root_regular_file() {
  local label="${1:?File label is required}"
  local path="${2:?File path is required}"

  test -f "${path}" && test ! -L "${path}" && test -r "${path}" || {
    fail "${label} is missing, unreadable, or not a regular non-symlink file: ${path}"
    return 1
  }
  test "$(stat --format '%u:%g' -- "${path}")" = '0:0' || {
    fail "${label} must be owned by root:root: ${path}"
    return 1
  }
  test "$(stat --format '%h' -- "${path}")" -eq 1 || {
    fail "${label} must have exactly one hard link: ${path}"
    return 1
  }
  if find "${path}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail "${label} must not be group- or world-writable: ${path}"
  fi
}

read_metadata_value() {
  local label="${1:?Metadata label is required}"
  local path="${2:?Metadata path is required}"

  require_root_regular_file "${label}" "${path}" || return $?
  test "$(wc -l <"${path}")" -eq 1 || {
    fail "${label} must contain exactly one line: ${path}"
    return 1
  }
  test "$(wc -c <"${path}")" -le 256 || {
    fail "${label} is unexpectedly large: ${path}"
    return 1
  }
  cat -- "${path}"
}

sha256_file() {
  sha256sum --binary -- "${1:?File path is required}" | awk '{print $1}'
}

verify_sha256() {
  local label="${1:?File label is required}"
  local path="${2:?File path is required}"
  local expected="${3:?Expected SHA-256 is required}"
  local actual

  require_root_regular_file "${label}" "${path}" || return $?
  actual="$(sha256_file "${path}")" || return $?
  test "${actual}" = "${expected}" ||
    fail "${label} SHA-256 mismatch: expected=${expected} actual=${actual}"
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
reserved = {".source-commit", ".release-id"}
files = []
total_size = 0
for directory, directory_names, file_names in os.walk(root, topdown=True, followlinks=False):
    current = Path(directory)
    for name in directory_names:
        path = current / name
        metadata = path.lstat()
        if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
            raise SystemExit("Web tree contains a non-directory or symlink node: " + str(path))
    for name in file_names:
        path = current / name
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
            raise SystemExit("Web tree contains a non-regular or symlink node: " + str(path))
        relative = path.relative_to(root).as_posix()
        if relative in reserved:
            continue
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

files.sort(key=lambda item: item[0].encode("utf-16-be", "surrogatepass"))
state = "\n".join(f"{name}:{size}:{digest}" for name, size, digest in files)
print(hashlib.sha256(state.encode("utf-8")).hexdigest())
PY
}

validate_current_release_link() {
  local label="${1:?Link label is required}"
  local link_path="${2:?Current link path is required}"
  local release_root="${3:?Release root is required}"
  local release_id="${4:?Release id is required}"
  local expected_target="${release_root}/${release_id}"
  local target

  test -L "${link_path}" || {
    fail "${label} current path is not a symlink: ${link_path}"
    return 1
  }
  target="$(readlink -f -- "${link_path}")" || return $?
  test "${target}" = "${expected_target}" || {
    fail "${label} current link does not target the pending FULL release: expected=${expected_target} actual=${target}"
    return 1
  }
  test -d "${target}" && test ! -L "${target}" || {
    fail "${label} release directory is missing or unsafe: ${target}"
    return 1
  }
  printf '%s\n' "${target}"
}

read_full_release_in_progress() {
  local commit
  local extra
  local previous_backend
  local previous_web
  local release_id

  test -d "${DEPLOY_STATE_DIRECTORY}" && test ! -L "${DEPLOY_STATE_DIRECTORY}" || {
    fail "Deploy state directory is missing or unsafe: ${DEPLOY_STATE_DIRECTORY}"
    return 1
  }
  test "$(stat --format '%u:%g' -- "${DEPLOY_STATE_DIRECTORY}")" = '0:0' || {
    fail 'Deploy state directory must be owned by root:root.'
    return 1
  }
  if find "${DEPLOY_STATE_DIRECTORY}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail 'Deploy state directory must not be group- or world-writable.'
  fi
  require_root_regular_file 'FULL release marker' "${FULL_RELEASE_IN_PROGRESS_FILE}" || return $?
  test "$(wc -l <"${FULL_RELEASE_IN_PROGRESS_FILE}")" -eq 1 || {
    fail 'FULL release marker must contain exactly one line.'
    return 1
  }
  validate_full_marker_size "$(wc -c <"${FULL_RELEASE_IN_PROGRESS_FILE}")" || return $?
  IFS=' ' read -r commit release_id previous_backend previous_web extra \
    <"${FULL_RELEASE_IN_PROGRESS_FILE}" || return $?
  test -z "${extra}" || {
    fail 'FULL release marker must contain exactly four fields.'
    return 1
  }
  validate_source_commit "${commit}" || return $?
  validate_release_id "${release_id}" || return $?
  [[ "${previous_backend}" =~ ^${BACKEND_RELEASE_ROOT//\//\/}/[A-Za-z0-9._-]+$ ]] || {
    fail 'Previous backend release in the FULL marker is invalid.'
    return 1
  }
  [[ "${previous_web}" =~ ^${WEB_RELEASE_ROOT//\//\/}/[A-Za-z0-9._-]+$ ]] || {
    fail 'Previous Web release in the FULL marker is invalid.'
    return 1
  }
  test "$(readlink -f -- "${previous_backend}")" = "${previous_backend}" || {
    fail 'Previous backend release in the FULL marker is not canonical.'
    return 1
  }
  test "$(readlink -f -- "${previous_web}")" = "${previous_web}" || {
    fail 'Previous Web release in the FULL marker is not canonical.'
    return 1
  }
  printf '%s %s %s %s\n' \
    "${commit}" "${release_id}" "${previous_backend}" "${previous_web}"
}

verify_served_index_sha256() {
  local expected_sha="${1:?Expected served index SHA-256 is required}"
  local served_sha

  served_sha="$(
    curl --fail --silent --show-error --max-time 15 \
      --noproxy '*' \
      --resolve 'www.sfgzt.cn:443:127.0.0.1' \
      --header 'Accept-Encoding: identity' \
      --header 'Cache-Control: no-cache' \
      "${WEB_HEALTH_URL}" |
      sha256sum --binary | awk '{print $1}'
  )" || return $?
  test "${served_sha}" = "${expected_sha}" || {
    fail "Served Web index SHA-256 mismatch: expected=${expected_sha} actual=${served_sha}"
    return 1
  }
}

select_latest_local_backup() {
  local backup_root="${1:?Backup root is required}"
  local latest_line

  # Current hosts store retained backups in the daily tier. Keep the legacy
  # root-level pattern during the transition so an older, otherwise healthy
  # installation can still pass the complete-release audit.
  latest_line="$({
    if test -d "${backup_root}/daily"; then
      find "${backup_root}/daily" \
        -maxdepth 1 -type f -name 'hotel_ai_os-daily-*.dump.enc' \
        -printf '%T@ %p\n'
    fi
    find "${backup_root}" \
      -maxdepth 1 -type f -name 'hotel_ai_os-auto-*.dump.enc' \
      -printf '%T@ %p\n'
  } | sort -nr | sed -n '1p')"
  test -n "${latest_line}" || {
    fail "No encrypted PostgreSQL backup exists below ${backup_root}."
    return 1
  }
  printf '%s\n' "${latest_line#* }"
}

verify_full_release_integrity() {
  local verified_commit="${1:?Verified source commit is required}"
  local verified_release_id="${2:?Verified release id is required}"
  local backend_target
  local expected_index_sha
  local expected_jar_sha
  local expected_runner_sha
  local expected_web_tree_sha
  local fingerprint_algorithm
  local web_target

  backend_target="$(validate_current_release_link \
    'Backend' "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}" "${verified_release_id}")" || return $?
  web_target="$(validate_current_release_link \
    'Web' "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}" "${verified_release_id}")" || return $?

  test "$(read_metadata_value 'Backend source identity' "${backend_target}/.source-commit")" = "${verified_commit}" || {
    fail 'Backend source identity differs from the pending FULL commit.'
    return 1
  }
  test "$(read_metadata_value 'Web source identity' "${web_target}/.source-commit")" = "${verified_commit}" || {
    fail 'Web source identity differs from the pending FULL commit.'
    return 1
  }
  test "$(read_metadata_value 'Backend release identity' "${backend_target}/.release-id")" = "${verified_release_id}" || {
    fail 'Backend release identity differs from the pending FULL release id.'
    return 1
  }
  test "$(read_metadata_value 'Web release identity' "${web_target}/.release-id")" = "${verified_release_id}" || {
    fail 'Web release identity differs from the pending FULL release id.'
    return 1
  }

  expected_jar_sha="$(read_metadata_value 'Core API identity' "${backend_target}/.core-api.sha256")" || return $?
  expected_runner_sha="$(read_metadata_value 'Migration runner identity' "${backend_target}/.migration-runner.sha256")" || return $?
  expected_index_sha="$(read_metadata_value 'Web index identity' "${backend_target}/.web-index.sha256")" || return $?
  expected_web_tree_sha="$(read_metadata_value 'Web tree identity' "${backend_target}/.web-tree.sha256")" || return $?
  fingerprint_algorithm="$(read_metadata_value \
    'Web tree fingerprint algorithm' "${backend_target}/.web-tree-fingerprint-algorithm")" || return $?
  validate_sha256 'Core API identity' "${expected_jar_sha}" || return $?
  validate_sha256 'Migration runner identity' "${expected_runner_sha}" || return $?
  validate_sha256 'Web index identity' "${expected_index_sha}" || return $?
  validate_sha256 'Web tree identity' "${expected_web_tree_sha}" || return $?
  test "${fingerprint_algorithm}" = "${WEB_TREE_FINGERPRINT_ALGORITHM}" || {
    fail "Unsupported Web tree fingerprint algorithm: ${fingerprint_algorithm}"
    return 1
  }

  verify_sha256 'Active Core API JAR' "${backend_target}/core-api.jar" "${expected_jar_sha}" || return $?
  verify_sha256 'Active migration runner' "${backend_target}/run-migration-once.sh" "${expected_runner_sha}" || return $?
  test -x "${backend_target}/run-migration-once.sh" || {
    fail 'Active migration runner is not executable.'
    return 1
  }
  verify_sha256 'Active Web index' "${web_target}/index.html" "${expected_index_sha}" || return $?
  test "$(read_web_tree_fingerprint "${web_target}")" = "${expected_web_tree_sha}" || {
    fail 'Active Web tree differs from the FULL release metadata.'
    return 1
  }
  verify_served_index_sha256 "${expected_index_sha}" || return $?

  printf 'FULL_RELEASE_INTEGRITY_OK sourceCommit=%s releaseId=%s backend=%s web=%s\n' \
    "${verified_commit}" "${verified_release_id}" "${backend_target}" "${web_target}"
}

main() {
  local full_record
  local previous_backend
  local previous_web
  local verified_full_commit
  local verified_full_release_id

  if test "$#" -ne 0; then
    printf '%s\n' 'Usage: 100-final-audit.sh' >&2
    exit 2
  fi
  test "$(id -u)" -eq 0 || {
    fail 'Final server audit must run as root.'
    exit 1
  }

  printf '%s\n' 'SERVICES'
  for service_name in ssh postgresql fail2ban clamav-freshclam unattended-upgrades hotel-ai-os-core-api; do
    printf '%s|%s|%s\n' \
      "${service_name}" \
      "$(systemctl is-enabled "${service_name}" 2>/dev/null || true)" \
      "$(systemctl is-active "${service_name}")"
  done
  printf 'caddy|%s|%s\n' \
    "$(systemctl is-enabled caddy 2>/dev/null || true)" \
    "$(systemctl is-active caddy 2>/dev/null || true)"

  printf '%s\n' 'API'
  curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health
  printf '\n'

  printf '%s\n' 'DATABASE'
  sudo -u postgres psql --dbname hotel_ai_os --tuples-only --no-align --command "
    select 'flyway|' || version || '|' || success
      from flyway_schema_history
     where installed_rank = (select max(installed_rank) from flyway_schema_history);
    select 'tables|' || count(*)
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r', 'p');
    select 'forced_rls|' || count(*)
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relkind in ('r', 'p')
       and c.relforcerowsecurity;
  "

  printf '%s\n' 'SECURE_FILES'
  stat -c '%U:%G:%a:%s:%n' \
    /etc/hotel-ai-os/bootstrap-secrets.env \
    /etc/hotel-ai-os/migration.env \
    /etc/hotel-ai-os/core-api.env \
    /etc/hotel-ai-os/pilot-accounts.tsv \
    /etc/hotel-ai-os/backup-encryption.key

  printf '%s\n' 'NETWORK'
  sudo ss -lntp
  sudo ufw status verbose
  sudo fail2ban-client status sshd

  printf '%s\n' 'AUTOMATION'
  systemctl is-enabled hotel-ai-os-postgres-backup.timer
  systemctl is-active hotel-ai-os-postgres-backup.timer
  systemctl is-enabled hotel-ai-os-health-check.timer
  systemctl is-active hotel-ai-os-health-check.timer
  systemctl list-timers \
    hotel-ai-os-postgres-backup.timer \
    hotel-ai-os-health-check.timer \
    --no-pager

  latest_backup="$(select_latest_local_backup /var/backups/hotel-ai-os/postgres)"
  sha256sum --check "${latest_backup}.sha256"
  stat -c '%U:%G:%a:%s:%n' "${latest_backup}" "${latest_backup}.sha256"

  printf '%s\n' 'RESOURCES'
  df -h /
  free -h
  swapon --show

  test ! -e /tmp/hotel-ai-os-release
  if test -f /var/run/reboot-required; then
    printf '%s\n' 'REBOOT_REQUIRED'
    exit 1
  fi

  if test -e "${FULL_RELEASE_IN_PROGRESS_FILE}" || test -L "${FULL_RELEASE_IN_PROGRESS_FILE}"; then
    test -x "${FAST_RELEASE_COMMAND}" ||
      fail 'FULL release coordinator is unavailable.'
    full_record="$(read_full_release_in_progress)" || exit $?
    IFS=' ' read -r \
      verified_full_commit verified_full_release_id previous_backend previous_web \
      <<<"${full_record}"
    verify_full_release_integrity "${verified_full_commit}" "${verified_full_release_id}"
    "${FAST_RELEASE_COMMAND}" MARK_FULL "${verified_full_commit}" "${verified_full_release_id}"
  fi
  printf '%s\n' 'NO_REBOOT_REQUIRED'
  printf '%s\n' 'FINAL_SERVER_AUDIT_OK'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
