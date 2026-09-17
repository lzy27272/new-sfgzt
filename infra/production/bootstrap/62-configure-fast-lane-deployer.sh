#!/bin/bash
set -Eeuo pipefail
shopt -s inherit_errexit

umask 027
export PATH='/usr/sbin:/usr/bin:/sbin:/bin'

readonly DEPLOY_USER='hotel-ai-deploy'
readonly DEPLOY_GROUP='hotel-ai-deploy'
readonly DEPLOY_HOME='/var/lib/hotel-ai-fast-deploy'
readonly FAST_STAGE_ROOT='/tmp/hotel-ai-os-fast-release'
readonly FAST_STAGE_QUARANTINE='/tmp/hotel-ai-os-fast-release/.quarantine'
readonly DEPLOY_STATE_DIRECTORY='/var/lib/hotel-ai-os-deploy'
readonly DEPLOY_SOURCE_COMMIT_FILE='/var/lib/hotel-ai-os-deploy/deployed-source-commit'
readonly DEPLOY_STATE_INCONSISTENT_FILE='/var/lib/hotel-ai-os-deploy/INCONSISTENT'
readonly FULL_RELEASE_IN_PROGRESS_FILE='/var/lib/hotel-ai-os-deploy/FULL_RELEASE_IN_PROGRESS'
readonly RELEASE_LOCK='/run/lock/hotel-ai-os-release.lock'
readonly BACKEND_RELEASE_ROOT='/opt/hotel-ai-os/releases'
readonly BACKEND_CURRENT_LINK='/opt/hotel-ai-os/current'
readonly WEB_RELEASE_ROOT='/srv/www/hotel-ai-os/releases'
readonly WEB_CURRENT_LINK='/srv/www/hotel-ai-os/current'
readonly CORE_SERVICE='hotel-ai-os-core-api.service'
readonly CORE_HEALTH_URL='http://127.0.0.1:18080/actuator/health'
readonly WEB_HEALTH_URL='https://www.sfgzt.cn/index.html'
readonly INSTALLED_COMMAND='/usr/local/sbin/hotel-ai-os-install-fast-lane-release'
readonly PREVIOUS_INSTALLED_COMMAND='/usr/local/sbin/hotel-ai-os-install-fast-lane-release.previous'
readonly SUDOERS_FILE='/etc/sudoers.d/91-hotel-ai-fast-deploy'

installer_pending=''
installer_backup_pending=''
installer_rollback_pending=''
key_pending=''
state_pending=''
sudoers_pending=''
sudoers_backup_pending=''
previous_installer_rollback_pending=''
trusted_installer_snapshot=''
trusted_public_key_snapshot=''
activation_transaction_armed=false
activation_had_installer=false
activation_had_previous_installer=false
activation_had_sudoers=false
preserve_activation_recovery_files=false

cleanup_pending_files() {
  local pending
  for pending in \
    "${installer_pending}" "${installer_backup_pending}" "${installer_rollback_pending}" \
    "${key_pending}" "${state_pending}" "${sudoers_pending}" \
    "${sudoers_backup_pending}" "${previous_installer_rollback_pending}" \
    "${trusted_installer_snapshot}" "${trusted_public_key_snapshot}"; do
    if test -n "${pending}"; then
      if test "${preserve_activation_recovery_files}" = true; then
        case "${pending}" in
          "${installer_backup_pending}"|"${installer_rollback_pending}"|\
          "${sudoers_backup_pending}"|"${previous_installer_rollback_pending}")
            continue
            ;;
        esac
      fi
      rm -f -- "${pending}" || true
    fi
  done
}

fail() {
  printf 'FAST_LANE_BOOTSTRAP_ERROR %s\n' "$*" >&2
  return 1
}

acquire_release_lock() {
  exec 9>"${RELEASE_LOCK}"
  if ! flock --nonblock 9; then
    fail 'another fast or complete release is already in progress'
    return 5
  fi
}

require_real_directory() {
  local label="${1:?Directory label is required}"
  local path="${2:?Directory path is required}"

  test -d "${path}" && test ! -L "${path}" ||
    fail "${label} must be a real directory, never a symlink: ${path}"
}

require_directory_state() {
  local label="${1:?Directory label is required}"
  local path="${2:?Directory path is required}"
  local expected="${3:?Expected owner, group, and mode are required}"

  require_real_directory "${label}" "${path}" || return $?
  test "$(stat --format '%U:%G:%a' -- "${path}")" = "${expected}" ||
    fail "${label} has unexpected owner, group, or mode: ${path}"
}

require_regular_file_state() {
  local label="${1:?File label is required}"
  local path="${2:?File path is required}"
  local expected="${3:?Expected owner, group, and mode are required}"

  test -f "${path}" && test ! -L "${path}" ||
    fail "${label} must be a regular non-symlink file: ${path}"
  test "$(stat --format '%U:%G:%a' -- "${path}")" = "${expected}" ||
    fail "${label} has unexpected owner, group, or mode: ${path}"
}

validate_active_release_link() {
  local label="${1:?Release label is required}"
  local link_path="${2:?Current link is required}"
  local release_root="${3:?Release root is required}"
  local release_id
  local target

  require_real_directory "${label} release root" "${release_root}" || return $?
  test "$(readlink -f -- "${release_root}")" = "${release_root}" || {
    fail "${label} release root must already be canonical: ${release_root}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${release_root}")" = 'root:root' || {
    fail "${label} release root must be owned by root:root: ${release_root}"
    return 1
  }
  if find "${release_root}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail "${label} release root must not be group- or world-writable: ${release_root}"
    return 1
  fi

  test -L "${link_path}" || {
    fail "${label} current path must be a symlink: ${link_path}"
    return 1
  }
  target="$(readlink -f -- "${link_path}")" || {
    fail "${label} current link cannot be resolved: ${link_path}"
    return 1
  }
  require_real_directory "${label} current release" "${target}" || return $?
  test "$(dirname -- "${target}")" = "${release_root}" || {
    fail "${label} current link must target a direct child of ${release_root}: ${target}"
    return 1
  }
  release_id="$(basename -- "${target}")"
  [[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] &&
    [[ "${release_id}" != *'..'* ]] || {
    fail "${label} current release has an unsafe release id: ${release_id}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${target}")" = 'root:root' || {
    fail "${label} current release must be owned by root:root: ${target}"
    return 1
  }
  if find "${target}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail "${label} current release must not be group- or world-writable: ${target}"
    return 1
  fi
  printf '%s\n' "${target}"
}

read_root_owned_source_identity() {
  local label="${1:?Release label is required}"
  local release_path="${2:?Release path is required}"
  local identity_file="${release_path}/.source-commit"
  local value

  test -f "${identity_file}" && test ! -L "${identity_file}" || {
    fail "${label} source identity must be a regular non-symlink file: ${identity_file}"
    return 1
  }
  test "$(stat --format '%U:%G' -- "${identity_file}")" = 'root:root' || {
    fail "${label} source identity must be owned by root:root: ${identity_file}"
    return 1
  }
  if find "${identity_file}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
    fail "${label} source identity must not be group- or world-writable: ${identity_file}"
    return 1
  fi
  test "$(wc -l <"${identity_file}")" -eq 1 &&
    test "$(wc -c <"${identity_file}")" -le 64 || {
    fail "${label} source identity must contain one bounded record"
    return 1
  }
  value="$(cat -- "${identity_file}")"
  [[ "${value}" =~ ^[0-9a-f]{40}$ ]] || {
    fail "${label} source identity is not a lowercase 40-character Git SHA"
    return 1
  }
  printf '%s\n' "${value}"
}

verify_active_baseline() {
  local expected_commit="${1:?Baseline commit is required}"
  local backend_commit
  local backend_target
  local current_index_sha
  local main_pid
  local running_release
  local served_index_sha
  local web_commit
  local web_target

  backend_target="$(validate_active_release_link \
    'Backend' "${BACKEND_CURRENT_LINK}" "${BACKEND_RELEASE_ROOT}")" || return $?
  web_target="$(validate_active_release_link \
    'Web' "${WEB_CURRENT_LINK}" "${WEB_RELEASE_ROOT}")" || return $?
  backend_commit="$(read_root_owned_source_identity 'Backend' "${backend_target}")" || return $?
  web_commit="$(read_root_owned_source_identity 'Web' "${web_target}")" || return $?
  test "${backend_commit}" = "${expected_commit}" || {
    fail "Backend current release differs from requested baseline: expected=${expected_commit} actual=${backend_commit}"
    return 1
  }
  test "${web_commit}" = "${expected_commit}" || {
    fail "Web current release differs from requested baseline: expected=${expected_commit} actual=${web_commit}"
    return 1
  }

  systemctl is-active --quiet "${CORE_SERVICE}" || {
    fail 'Core API service is not active at the requested baseline'
    return 1
  }
  curl --fail --silent --show-error --max-time 5 "${CORE_HEALTH_URL}" >/dev/null || {
    fail 'Core API health endpoint did not accept the requested baseline'
    return 1
  }
  main_pid="$(systemctl show "${CORE_SERVICE}" --property MainPID --value)"
  [[ "${main_pid}" =~ ^[1-9][0-9]*$ ]] || {
    fail "Core API service returned an invalid MainPID: ${main_pid}"
    return 1
  }
  running_release="$(readlink -f -- "/proc/${main_pid}/cwd" 2>/dev/null)" || {
    fail 'Core API process working directory could not be resolved'
    return 1
  }
  test "${running_release}" = "${backend_target}" || {
    fail "Core API process is not running from Backend current: expected=${backend_target} actual=${running_release}"
    return 1
  }

  test -f "${web_target}/index.html" && test ! -L "${web_target}/index.html" || {
    fail "Web current index must be a regular non-symlink file: ${web_target}/index.html"
    return 1
  }
  current_index_sha="$(sha256sum --binary -- "${web_target}/index.html" | awk '{print $1}')" || return $?
  served_index_sha="$({
    curl --fail --silent --show-error --max-time 15 \
      --noproxy '*' \
      --resolve 'www.sfgzt.cn:443:127.0.0.1' \
      --header 'Accept-Encoding: identity' \
      --header 'Cache-Control: no-cache' \
      "${WEB_HEALTH_URL}" |
      sha256sum --binary | awk '{print $1}'
  })" || {
    fail 'HTTPS Web baseline could not be fetched and hashed'
    return 1
  }
  test "${served_index_sha}" = "${current_index_sha}" || {
    fail "HTTPS Web content differs from Web current index: expected=${current_index_sha} actual=${served_index_sha}"
    return 1
  }

  printf 'FAST_LANE_BASELINE_VERIFIED sourceCommit=%s backend=%s web=%s indexSha256=%s\n' \
    "${expected_commit}" "${backend_target}" "${web_target}" "${current_index_sha}"
}

require_root_controlled_parent_chain() {
  local path="${1:?Input path is required}"
  local parent

  parent="$(dirname -- "$(realpath --canonicalize-missing -- "${path}")")"
  while test "${parent}" != '/'; do
    require_real_directory 'trusted input parent' "${parent}" || return $?
    test "$(stat --format '%U:%G' -- "${parent}")" = 'root:root' ||
      fail "trusted input parent must be owned by root:root: ${parent}"
    if find "${parent}" -maxdepth 0 -perm /022 -print -quit | grep -q .; then
      fail "trusted input parent must not be group- or world-writable: ${parent}"
      return 1
    fi
    parent="$(dirname -- "${parent}")"
  done
}

capture_trusted_input_snapshots() {
  local installer_fd
  local installer_state
  local key_fd

  require_root_controlled_parent_chain "${public_key_file}" || return $?
  require_root_controlled_parent_chain "${installer_source}" || return $?
  exec {key_fd}<"${public_key_file}"
  test ! -L "${public_key_file}" && test "${public_key_file}" -ef "/proc/self/fd/${key_fd}" ||
    fail 'public key path changed while it was opened'
  test "$(stat -L --format '%U:%G:%a' -- "/proc/self/fd/${key_fd}")" = 'root:root:600' ||
    fail 'public key input must be root-owned with mode 0600'
  trusted_public_key_snapshot="$(mktemp /run/hotel-ai-fast-deploy-key.XXXXXXXX)"
  cat <&"${key_fd}" >"${trusted_public_key_snapshot}"
  exec {key_fd}<&-
  chown root:root "${trusted_public_key_snapshot}"
  chmod 0600 "${trusted_public_key_snapshot}"

  exec {installer_fd}<"${installer_source}"
  test ! -L "${installer_source}" && test "${installer_source}" -ef "/proc/self/fd/${installer_fd}" ||
    fail 'installer source path changed while it was opened'
  installer_state="$(stat -L --format '%U:%G:%a' -- "/proc/self/fd/${installer_fd}")"
  case "${installer_state}" in
    'root:root:644'|'root:root:755') ;;
    *) fail 'installer source must be root-owned and mode 0644 or 0755'; return $? ;;
  esac
  trusted_installer_snapshot="$(mktemp /run/hotel-ai-fast-deploy-installer.XXXXXXXX)"
  cat <&"${installer_fd}" >"${trusted_installer_snapshot}"
  exec {installer_fd}<&-
  chown root:root "${trusted_installer_snapshot}"
  chmod 0600 "${trusted_installer_snapshot}"
}

validate_deploy_account_if_present() {
  local deploy_account
  local deploy_groups
  local deploy_home
  local deploy_passwd_record
  local deploy_shell
  local deploy_uid

  if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
    return 0
  fi
  getent group "${DEPLOY_GROUP}" >/dev/null ||
    fail 'dedicated deploy user exists but its required primary group is missing'
  deploy_passwd_record="$(getent passwd "${DEPLOY_USER}")"
  IFS=: read -r deploy_account _deploy_password deploy_uid _deploy_gid _deploy_gecos deploy_home deploy_shell \
    <<<"${deploy_passwd_record}"
  test "${deploy_account}" = "${DEPLOY_USER}" ||
    fail 'dedicated deploy account lookup is inconsistent'
  [[ "${deploy_uid}" =~ ^[0-9]+$ ]] && test "${deploy_uid}" -ne 0 ||
    fail 'dedicated deploy account must have a non-root numeric UID'
  test "$(id -g -n "${DEPLOY_USER}")" = "${DEPLOY_GROUP}" ||
    fail 'dedicated deploy user has an unexpected primary group'
  test "${deploy_home}" = "${DEPLOY_HOME}" ||
    fail "dedicated deploy user has an unexpected passwd home: ${deploy_home}"
  test "${deploy_shell}" = '/bin/bash' ||
    fail "dedicated deploy user has an unexpected login shell: ${deploy_shell}"
  deploy_groups="$(id -nG "${DEPLOY_USER}" | tr ' ' '\n' | sed '/^$/d' | sort -u | paste -sd ' ' -)"
  test "${deploy_groups}" = "${DEPLOY_GROUP}" ||
    fail "dedicated deploy user must not have supplementary groups: ${deploy_groups}"
  test "$(passwd -S "${DEPLOY_USER}" | awk '{print $2}')" = 'L' ||
    fail 'dedicated deploy user password must remain locked'
}

validate_preexisting_layout() {
  local authorized_keys="${DEPLOY_HOME}/.ssh/authorized_keys"
  local existing_state=''
  local has_installer=false
  local has_sudoers=false
  local ssh_directory="${DEPLOY_HOME}/.ssh"
  local staging_directory="${DEPLOY_STATE_DIRECTORY}/staging"

  if test -e "${DEPLOY_HOME}" || test -L "${DEPLOY_HOME}"; then
    getent group "${DEPLOY_GROUP}" >/dev/null ||
      fail 'dedicated deploy home exists before its required group'
    require_directory_state 'dedicated deploy home' "${DEPLOY_HOME}" "root:${DEPLOY_GROUP}:750" || return $?
  fi
  if test -e "${ssh_directory}" || test -L "${ssh_directory}"; then
    require_directory_state 'dedicated deploy SSH directory' "${ssh_directory}" "root:${DEPLOY_GROUP}:750" || return $?
  fi
  if test -e "${authorized_keys}" || test -L "${authorized_keys}"; then
    require_regular_file_state 'dedicated deploy authorized_keys' "${authorized_keys}" 'root:root:644' || return $?
  fi

  if test -e "${FAST_STAGE_ROOT}" || test -L "${FAST_STAGE_ROOT}"; then
    require_real_directory 'fast staging root' "${FAST_STAGE_ROOT}" || return $?
    existing_state="$(stat --format '%U:%G:%a' -- "${FAST_STAGE_ROOT}")"
    case "${existing_state}" in
      "root:${DEPLOY_GROUP}:1730"|"root:${DEPLOY_GROUP}:700"|'root:root:700') ;;
      *) fail 'existing fast staging root is not a recognized safe bootstrap state'; return $? ;;
    esac
  fi
  if test -e "${FAST_STAGE_QUARANTINE}" || test -L "${FAST_STAGE_QUARANTINE}"; then
    require_directory_state 'fast staging quarantine' "${FAST_STAGE_QUARANTINE}" 'root:root:700' || return $?
  fi

  if test -e "${DEPLOY_STATE_DIRECTORY}" || test -L "${DEPLOY_STATE_DIRECTORY}"; then
    require_directory_state 'deploy state directory' "${DEPLOY_STATE_DIRECTORY}" 'root:root:750' || return $?
  fi
  if test -e "${staging_directory}" || test -L "${staging_directory}"; then
    require_directory_state 'verified staging directory' "${staging_directory}" 'root:root:700' || return $?
  fi
  if test -e "${DEPLOY_SOURCE_COMMIT_FILE}" || test -L "${DEPLOY_SOURCE_COMMIT_FILE}"; then
    require_regular_file_state 'deployed source state' "${DEPLOY_SOURCE_COMMIT_FILE}" 'root:root:640' || return $?
    test "$(cat -- "${DEPLOY_SOURCE_COMMIT_FILE}")" = "${baseline_commit}" ||
      fail 'existing deployed-source-commit differs; do not rewrite it outside a verified full release'
  fi
  test ! -e "${DEPLOY_STATE_INCONSISTENT_FILE}" && test ! -L "${DEPLOY_STATE_INCONSISTENT_FILE}" ||
    fail 'deploy state is marked inconsistent; repair it before changing the deployer'
  test ! -e "${FULL_RELEASE_IN_PROGRESS_FILE}" && test ! -L "${FULL_RELEASE_IN_PROGRESS_FILE}" ||
    fail 'a complete release is in progress; do not change the deployer policy'

  if test -e "${INSTALLED_COMMAND}" || test -L "${INSTALLED_COMMAND}"; then
    require_regular_file_state 'installed fast-lane command' "${INSTALLED_COMMAND}" 'root:root:755' || return $?
    has_installer=true
  fi
  if test -e "${PREVIOUS_INSTALLED_COMMAND}" || test -L "${PREVIOUS_INSTALLED_COMMAND}"; then
    require_regular_file_state 'previous fast-lane command backup' "${PREVIOUS_INSTALLED_COMMAND}" 'root:root:700' || return $?
  fi
  if test -e "${SUDOERS_FILE}" || test -L "${SUDOERS_FILE}"; then
    require_regular_file_state 'dedicated deploy sudoers policy' "${SUDOERS_FILE}" 'root:root:440' || return $?
    has_sudoers=true
  fi
  test "${has_installer}" = "${has_sudoers}" ||
    fail 'installed fast-lane command and dedicated sudoers policy must either both exist or both be absent'
  visudo -cf /etc/sudoers >/dev/null ||
    fail 'existing global sudoers policy is invalid; repair it before changing the deployer'
}

prepare_root_owned_ssh_layout() {
  local ssh_directory="${DEPLOY_HOME}/.ssh"

  if ! test -e "${DEPLOY_HOME}"; then
    mkdir --mode=0750 -- "${DEPLOY_HOME}"
  fi
  chown --no-dereference "root:${DEPLOY_GROUP}" "${DEPLOY_HOME}"
  chmod 0750 "${DEPLOY_HOME}"
  if ! test -e "${ssh_directory}"; then
    mkdir --mode=0750 -- "${ssh_directory}"
  fi
  chown --no-dereference "root:${DEPLOY_GROUP}" "${ssh_directory}"
  chmod 0750 "${ssh_directory}"
}

prepare_authorized_key_candidate() {
  local ssh_directory="${DEPLOY_HOME}/.ssh"

  key_pending="$(mktemp --tmpdir="${ssh_directory}" '.authorized_keys.XXXXXXXX')"
  {
    printf '%s ' 'restrict'
    cat -- "${trusted_public_key_snapshot}"
  } >"${key_pending}"
  chown root:root "${key_pending}"
  chmod 0644 "${key_pending}"
  test "$(wc -l <"${key_pending}")" -eq 1 || fail 'prepared authorized key must contain exactly one record'
  ssh-keygen -l -f "${key_pending}" >/dev/null
}

prepare_fast_stage_layout() {
  if test -e "${FAST_STAGE_ROOT}"; then
    chown --no-dereference root:root "${FAST_STAGE_ROOT}"
    chmod 0700 "${FAST_STAGE_ROOT}"
  else
    mkdir --mode=0700 -- "${FAST_STAGE_ROOT}"
    chown --no-dereference root:root "${FAST_STAGE_ROOT}"
  fi
  if ! test -e "${FAST_STAGE_QUARANTINE}"; then
    mkdir --mode=0700 -- "${FAST_STAGE_QUARANTINE}"
    chown --no-dereference root:root "${FAST_STAGE_QUARANTINE}"
  fi
  chown --no-dereference "root:${DEPLOY_GROUP}" "${FAST_STAGE_ROOT}"
  chmod 1730 "${FAST_STAGE_ROOT}"
  require_directory_state 'fast staging root' "${FAST_STAGE_ROOT}" "root:${DEPLOY_GROUP}:1730"
  require_directory_state 'fast staging quarantine' "${FAST_STAGE_QUARANTINE}" 'root:root:700'
}

prepare_deploy_state_layout() {
  local staging_directory="${DEPLOY_STATE_DIRECTORY}/staging"

  if ! test -e "${DEPLOY_STATE_DIRECTORY}"; then
    mkdir --mode=0750 -- "${DEPLOY_STATE_DIRECTORY}"
    chown --no-dereference root:root "${DEPLOY_STATE_DIRECTORY}"
  fi
  if ! test -e "${staging_directory}"; then
    mkdir --mode=0700 -- "${staging_directory}"
    chown --no-dereference root:root "${staging_directory}"
  fi
}

prepare_baseline_state() {
  if test -e "${DEPLOY_SOURCE_COMMIT_FILE}"; then
    return 0
  fi
  state_pending="$(mktemp --tmpdir="${DEPLOY_STATE_DIRECTORY}" '.deployed-source-commit.bootstrap.XXXXXXXX')"
  printf '%s\n' "${baseline_commit}" >"${state_pending}"
  chown root:root "${state_pending}"
  chmod 0640 "${state_pending}"
  sync -f "${state_pending}"
  mv --no-target-directory -- "${state_pending}" "${DEPLOY_SOURCE_COMMIT_FILE}"
  state_pending=''
}

prepare_installer_candidate() {
  installer_pending="$(mktemp /usr/local/sbin/.hotel-ai-os-install-fast-lane-release.XXXXXXXX)"
  install -o root -g root -m 0755 "${trusted_installer_snapshot}" "${installer_pending}"
  test "$(sha256sum -- "${trusted_installer_snapshot}" | awk '{print $1}')" = \
    "$(sha256sum -- "${installer_pending}" | awk '{print $1}')" ||
    fail 'prepared fast-lane command differs from its trusted source'
}

prepare_sudoers_candidate() {
  sudoers_pending="$(mktemp /etc/sudoers.d/.91-hotel-ai-fast-deploy.XXXXXXXX)"
  cat >"${sudoers_pending}" <<'EOF'
Defaults:hotel-ai-deploy !setenv, secure_path="/usr/sbin:/usr/bin:/sbin:/bin"
Cmnd_Alias HOTEL_AI_OS_FAST_RELEASE = /usr/local/sbin/hotel-ai-os-install-fast-lane-release STATUS, \
    /usr/local/sbin/hotel-ai-os-install-fast-lane-release POLICY, \
    /usr/local/sbin/hotel-ai-os-install-fast-lane-release VERIFY WEB_ONLY *, \
    /usr/local/sbin/hotel-ai-os-install-fast-lane-release WEB_ONLY *
hotel-ai-deploy ALL=(root) NOPASSWD: HOTEL_AI_OS_FAST_RELEASE
EOF
  chmod 0440 "${sudoers_pending}"
  chown root:root "${sudoers_pending}"
  visudo -cf "${sudoers_pending}"
}

reserve_absent_path() {
  local template="${1:?Temporary path template is required}"
  local reserved

  reserved="$(mktemp "${template}")" || return $?
  rm -f -- "${reserved}" || return $?
  printf '%s\n' "${reserved}"
}

prepare_activation_transaction() {
  activation_had_installer=false
  activation_had_previous_installer=false
  activation_had_sudoers=false

  if test -e "${INSTALLED_COMMAND}"; then
    activation_had_installer=true
    installer_rollback_pending="$(reserve_absent_path \
      /usr/local/sbin/.hotel-ai-os-install-fast-lane-release.rollback.XXXXXXXX)"
    installer_backup_pending="$(mktemp \
      /usr/local/sbin/.hotel-ai-os-install-fast-lane-release.previous.XXXXXXXX)"
    install -o root -g root -m 0700 "${INSTALLED_COMMAND}" "${installer_backup_pending}"
  fi
  if test -e "${PREVIOUS_INSTALLED_COMMAND}"; then
    activation_had_previous_installer=true
    previous_installer_rollback_pending="$(reserve_absent_path \
      /usr/local/sbin/.hotel-ai-os-install-fast-lane-release.previous.rollback.XXXXXXXX)"
  fi
  if test -e "${SUDOERS_FILE}"; then
    activation_had_sudoers=true
    sudoers_backup_pending="$(reserve_absent_path \
      /etc/sudoers.d/.91-hotel-ai-fast-deploy.rollback.XXXXXXXX)"
  fi
}

rollback_activation_transaction() {
  local rollback_status=0

  test "${activation_transaction_armed}" = true || return 0

  # Remove the policy first. During rollback there is no interval in which the
  # deploy identity can invoke an old wrapper with a new policy, or vice versa.
  if test "${activation_had_sudoers}" = false || test -e "${sudoers_backup_pending}"; then
    rm -f -- "${SUDOERS_FILE}" || rollback_status=1
  fi

  if test -e "${installer_rollback_pending}"; then
    rm -f -- "${INSTALLED_COMMAND}" || rollback_status=1
    if mv --force --no-target-directory -- \
        "${installer_rollback_pending}" "${INSTALLED_COMMAND}"; then
      installer_rollback_pending=''
    else
      rollback_status=1
    fi
  elif test "${activation_had_installer}" = false; then
    rm -f -- "${INSTALLED_COMMAND}" || rollback_status=1
  fi

  if test -e "${previous_installer_rollback_pending}"; then
    rm -f -- "${PREVIOUS_INSTALLED_COMMAND}" || rollback_status=1
    if mv --force --no-target-directory -- \
        "${previous_installer_rollback_pending}" "${PREVIOUS_INSTALLED_COMMAND}"; then
      previous_installer_rollback_pending=''
    else
      rollback_status=1
    fi
  elif test "${activation_had_previous_installer}" = false; then
    rm -f -- "${PREVIOUS_INSTALLED_COMMAND}" || rollback_status=1
  fi

  if test -e "${sudoers_backup_pending}"; then
    if mv --force --no-target-directory -- "${sudoers_backup_pending}" "${SUDOERS_FILE}"; then
      sudoers_backup_pending=''
    else
      rollback_status=1
    fi
  fi
  activation_transaction_armed=false

  if test "${activation_had_installer}" = true; then
    require_regular_file_state \
      'restored fast-lane command' "${INSTALLED_COMMAND}" 'root:root:755' || rollback_status=1
  elif test -e "${INSTALLED_COMMAND}" || test -L "${INSTALLED_COMMAND}"; then
    rollback_status=1
  fi
  if test "${activation_had_sudoers}" = true; then
    require_regular_file_state \
      'restored deploy sudoers policy' "${SUDOERS_FILE}" 'root:root:440' || rollback_status=1
    visudo -cf /etc/sudoers >/dev/null || rollback_status=1
  elif test -e "${SUDOERS_FILE}" || test -L "${SUDOERS_FILE}"; then
    rollback_status=1
  fi
  if test "${activation_had_previous_installer}" = true; then
    require_regular_file_state \
      'restored previous command backup' "${PREVIOUS_INSTALLED_COMMAND}" 'root:root:700' || rollback_status=1
  elif test -e "${PREVIOUS_INSTALLED_COMMAND}" || test -L "${PREVIOUS_INSTALLED_COMMAND}"; then
    rollback_status=1
  fi

  test "${rollback_status}" -eq 0 || {
    preserve_activation_recovery_files=true
    printf '%s\n' \
      'FAST_LANE_BOOTSTRAP_ROLLBACK_ERROR installer/sudoers restoration requires root recovery' >&2
    printf 'FAST_LANE_BOOTSTRAP_RECOVERY_PATHS installer=%s sudoers=%s previous=%s previousCandidate=%s\n' \
      "${installer_rollback_pending:-<none>}" \
      "${sudoers_backup_pending:-<none>}" \
      "${previous_installer_rollback_pending:-<none>}" \
      "${installer_backup_pending:-<none>}" >&2
    return 1
  }
  printf '%s\n' 'FAST_LANE_BOOTSTRAP_ROLLBACK_COMPLETE installer_and_sudoers=restored' >&2
}

cleanup_on_exit() {
  local exit_status=$?

  trap - EXIT HUP INT TERM
  if test "${activation_transaction_armed}" = true; then
    rollback_activation_transaction || exit_status=1
  fi
  cleanup_pending_files
  exit "${exit_status}"
}

activate_installer_and_sudoers() {
  prepare_activation_transaction
  activation_transaction_armed=true

  # Revoke the dedicated sudo policy before the first wrapper mutation. The
  # account therefore observes either the complete old pair, no pair while the
  # transaction is in flight, or the complete validated new pair.
  if test "${activation_had_sudoers}" = true; then
    mv --no-target-directory -- "${SUDOERS_FILE}" "${sudoers_backup_pending}"
  fi
  if test "${activation_had_installer}" = true; then
    mv --no-target-directory -- "${INSTALLED_COMMAND}" "${installer_rollback_pending}"
  fi
  mv --no-target-directory -- "${installer_pending}" "${INSTALLED_COMMAND}"
  installer_pending=''
  mv --no-target-directory -- "${sudoers_pending}" "${SUDOERS_FILE}"
  sudoers_pending=''

  if ! visudo -cf /etc/sudoers; then
    fail 'global sudoers validation failed; rolling back the installer and policy pair'
    return 1
  fi

  if test "${activation_had_installer}" = true; then
    if test "${activation_had_previous_installer}" = true; then
      mv --no-target-directory -- \
        "${PREVIOUS_INSTALLED_COMMAND}" "${previous_installer_rollback_pending}"
    fi
    mv --no-target-directory -- "${installer_backup_pending}" "${PREVIOUS_INSTALLED_COMMAND}"
    installer_backup_pending=''
    require_regular_file_state \
      'previous fast-lane command backup' "${PREVIOUS_INSTALLED_COMMAND}" 'root:root:700'
  fi
  require_regular_file_state \
    'installed fast-lane command' "${INSTALLED_COMMAND}" 'root:root:755'
  require_regular_file_state \
    'dedicated deploy sudoers policy' "${SUDOERS_FILE}" 'root:root:440'

  activation_transaction_armed=false
  rm -f -- "${installer_rollback_pending}" "${sudoers_backup_pending}" \
    "${previous_installer_rollback_pending}" || true
  installer_rollback_pending=''
  sudoers_backup_pending=''
  previous_installer_rollback_pending=''
}

main() {
  if test "$(id -u)" -ne 0; then
    printf '%s\n' 'This bootstrap must run as root.' >&2
    exit 1
  fi
  if test "$#" -ne 2; then
    printf '%s\n' 'Usage: 62-configure-fast-lane-deployer.sh <root-owned-public-key-file> <deployed-40-char-source-commit>' >&2
    exit 2
  fi

  public_key_file="$1"
  baseline_commit="$2"
  script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  installer_source="${script_directory}/61-install-fast-lane-release.sh"
  trap cleanup_on_exit EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM

  [[ "${baseline_commit}" =~ ^[0-9a-f]{40}$ ]] || {
    printf '%s\n' 'Baseline commit must be a lowercase 40-character Git SHA.' >&2
    exit 2
  }
  test -f "${public_key_file}" && test ! -L "${public_key_file}" || {
    printf '%s\n' 'Public key input must be a regular non-symlink file.' >&2
    exit 2
  }
  test -f "${installer_source}" && test ! -L "${installer_source}" || {
    printf '%s\n' 'Installer source must be a regular non-symlink file.' >&2
    exit 2
  }

  # The release lock is the only coordination write allowed before the active
  # baseline is proven. All account, directory, state, key, wrapper, and policy
  # mutations happen after this check.
  acquire_release_lock
  verify_active_baseline "${baseline_commit}"
  validate_deploy_account_if_present
  validate_preexisting_layout

  capture_trusted_input_snapshots
  test "$(wc -l <"${trusted_public_key_snapshot}")" -eq 1 || {
    printf '%s\n' 'Public key input must contain exactly one key.' >&2
    exit 2
  }
  ssh-keygen -l -f "${trusted_public_key_snapshot}" >/dev/null

  # Build and validate inactive candidates before changing the account or any
  # active authorization file.
  prepare_installer_candidate
  prepare_sudoers_candidate

  if ! getent group "${DEPLOY_GROUP}" >/dev/null; then
    groupadd --system "${DEPLOY_GROUP}"
  fi
  if ! id "${DEPLOY_USER}" >/dev/null 2>&1; then
    useradd --system --gid "${DEPLOY_GROUP}" --no-create-home \
      --home-dir "${DEPLOY_HOME}" --shell /bin/bash "${DEPLOY_USER}"
  fi
  validate_deploy_account_if_present

  prepare_root_owned_ssh_layout
  prepare_authorized_key_candidate
  prepare_fast_stage_layout
  prepare_deploy_state_layout
  prepare_baseline_state

  # Install the wrapper and sudo contract as one fail-closed transaction before
  # rotating the SSH key. Every error or handled signal restores both originals;
  # an initially absent wrapper is removed rather than sourced from stale backup.
  activate_installer_and_sudoers
  mv --force --no-target-directory -- "${key_pending}" "${DEPLOY_HOME}/.ssh/authorized_keys"
  key_pending=''

  require_regular_file_state 'installed fast-lane command' "${INSTALLED_COMMAND}" 'root:root:755'
  require_regular_file_state 'dedicated deploy sudoers policy' "${SUDOERS_FILE}" 'root:root:440'
  require_regular_file_state 'dedicated deploy authorized_keys' "${DEPLOY_HOME}/.ssh/authorized_keys" 'root:root:644'
  require_regular_file_state 'deployed source state' "${DEPLOY_SOURCE_COMMIT_FILE}" 'root:root:640'
  require_directory_state 'fast staging root' "${FAST_STAGE_ROOT}" "root:${DEPLOY_GROUP}:1730"
  require_directory_state 'fast staging quarantine' "${FAST_STAGE_QUARANTINE}" 'root:root:700'
  require_directory_state 'verified staging directory' "${DEPLOY_STATE_DIRECTORY}/staging" 'root:root:700'

  printf 'FAST_LANE_DEPLOYER_CONFIGURED user=%s baseline=%s installer=%s\n' \
    "${DEPLOY_USER}" "${baseline_commit}" "${INSTALLED_COMMAND}"
  printf '%s\n' 'Audit the effective privileges with: sudo -l -U hotel-ai-deploy'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
