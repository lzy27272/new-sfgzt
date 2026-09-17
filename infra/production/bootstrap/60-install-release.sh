#!/bin/bash
set -Eeuo pipefail
shopt -s inherit_errexit
umask 077

PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
export PATH
readonly PATH

readonly FAST_RELEASE_COMMAND='/usr/local/sbin/hotel-ai-os-install-fast-lane-release'
readonly STAGE_ROOT='/tmp/hotel-ai-os-release'
readonly BACKEND_RELEASE_ROOT='/opt/hotel-ai-os/releases'
readonly WEB_RELEASE_ROOT='/srv/www/hotel-ai-os/releases'
readonly WEB_TREE_FINGERPRINT_ALGORITHM='sha256(sorted-path-colon-size-colon-content-sha256-v1)'

candidate_cleanup_armed=false
backend_candidate=''
web_candidate=''

fail() {
  printf '%s\n' "$*" >&2
  return 1
}

validate_sha256() {
  local label="${1:?SHA label is required}"
  local value="${2-}"

  [[ "${value}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "${label} must be a lowercase SHA-256 digest."
}

require_regular_readable_file() {
  local label="${1:?File label is required}"
  local path="${2:?File path is required}"

  test -f "${path}" && test ! -L "${path}" && test -r "${path}" || {
    fail "${label} is missing, unreadable, or not a regular non-symlink file: ${path}"
    return 1
  }
  test "$(stat --format '%h' -- "${path}")" -eq 1 || {
    fail "${label} must have exactly one hard link: ${path}"
    return 1
  }
}

sha256_file() {
  sha256sum --binary -- "${1:?File path is required}" | awk '{print $1}'
}

verify_sha256() {
  local label="${1:?File label is required}"
  local path="${2:?File path is required}"
  local expected="${3:?Expected SHA-256 is required}"
  local actual

  require_regular_readable_file "${label}" "${path}" || return $?
  actual="$(sha256_file "${path}")" || return $?
  test "${actual}" = "${expected}" ||
    fail "${label} SHA-256 mismatch: expected=${expected} actual=${actual}"
}

validate_trusted_stage_tree() {
  test -d /tmp && test ! -L /tmp || {
    fail '/tmp must be a real directory.'
    return 1
  }
  test "$(stat --format '%u:%g' -- /tmp)" = '0:0' || {
    fail '/tmp must be owned by root:root.'
    return 1
  }
  find /tmp -maxdepth 0 -perm -1000 -print -quit | grep -q . || {
    fail '/tmp must retain the sticky bit.'
    return 1
  }
  test -d "${STAGE_ROOT}" && test ! -L "${STAGE_ROOT}" || {
    fail "Release stage is missing or unsafe: ${STAGE_ROOT}"
    return 1
  }
  test "$(stat --format '%u:%g' -- "${STAGE_ROOT}")" = '0:0' || {
    fail 'Release stage must be owned by root:root.'
    return 1
  }
  if find -P "${STAGE_ROOT}" -xdev \( -type l -o \( ! -type d ! -type f \) \) -print -quit |
      grep -q .; then
    fail 'Release stage contains a symlink or unsupported node.'
    return 1
  fi
  if find -P "${STAGE_ROOT}" -xdev \( ! -uid 0 -o ! -gid 0 \) -print -quit | grep -q .; then
    fail 'Every release stage node must be owned by root:root.'
    return 1
  fi
  if find -P "${STAGE_ROOT}" -xdev -perm /022 -print -quit | grep -q .; then
    fail 'Release stage nodes must not be group- or world-writable.'
    return 1
  fi
  if find -P "${STAGE_ROOT}" -xdev -type f ! -links 1 -print -quit | grep -q .; then
    fail 'Release stage files must have exactly one hard link.'
    return 1
  fi
}

cleanup_failed_candidate() {
  local status=$?
  local target

  trap - EXIT
  if test "${candidate_cleanup_armed}" = true; then
    for target in "${backend_candidate}" "${web_candidate}"; do
      case "${target}" in
        "${BACKEND_RELEASE_ROOT}/"*|"${WEB_RELEASE_ROOT}/"*) ;;
        '') continue ;;
        *)
          printf 'Refusing to clean unexpected candidate path: %s\n' "${target}" >&2
          continue
          ;;
      esac
      if test -L "${target}"; then
        printf 'Refusing to clean symlink candidate path: %s\n' "${target}" >&2
        continue
      fi
      if test -d "${target}"; then
        rm -rf --one-file-system -- "${target}" ||
          printf 'Failed to remove incomplete candidate: %s\n' "${target}" >&2
      fi
    done
  fi
  exit "${status}"
}

# Fingerprint only the deployable Web artifact. The two root-level identity
# files are written by this installer after the artifact has been verified and
# are therefore ignored only while re-verifying an installed release.
read_web_tree_fingerprint() {
  local tree_root="${1:?Web tree root is required}"
  local tree_mode="${2:-artifact}"

  test -d "${tree_root}" && test ! -L "${tree_root}" || {
    fail "Web tree is missing or unsafe: ${tree_root}"
    return 1
  }
  test "${tree_mode}" = 'artifact' || test "${tree_mode}" = 'installed' || {
    fail "Unsupported Web tree fingerprint mode: ${tree_mode}"
    return 2
  }

  python3 -I - "${tree_root}" "${tree_mode}" <<'PY'
import hashlib
import os
import stat
import sys
from pathlib import Path

root = Path(sys.argv[1])
mode = sys.argv[2]
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
            if mode == "installed":
                continue
            raise SystemExit("Web artifact uses a reserved release metadata path: " + relative)
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

# .NET StringComparer.Ordinal compares UTF-16 code units. Match the manifest
# producer exactly so Windows CI and the Linux release host share one contract.
files.sort(key=lambda item: item[0].encode("utf-16-be", "surrogatepass"))
state = "\n".join(f"{name}:{size}:{digest}" for name, size, digest in files)
print(hashlib.sha256(state.encode("utf-8")).hexdigest())
PY
}

write_release_metadata() {
  local backend_release="${1:?Backend release path is required}"
  local web_release="${2:?Web release path is required}"
  local source_commit="${3:?Source commit is required}"
  local release_id="${4:?Release id is required}"
  local jar_sha="${5:?JAR SHA-256 is required}"
  local runner_sha="${6:?Runner SHA-256 is required}"
  local index_sha="${7:?Index SHA-256 is required}"
  local web_tree_sha="${8:?Web tree SHA-256 is required}"

  printf '%s\n' "${source_commit}" >"${backend_release}/.source-commit"
  printf '%s\n' "${source_commit}" >"${web_release}/.source-commit"
  printf '%s\n' "${release_id}" >"${backend_release}/.release-id"
  printf '%s\n' "${release_id}" >"${web_release}/.release-id"
  printf '%s\n' "${jar_sha}" >"${backend_release}/.core-api.sha256"
  printf '%s\n' "${runner_sha}" >"${backend_release}/.migration-runner.sha256"
  printf '%s\n' "${index_sha}" >"${backend_release}/.web-index.sha256"
  printf '%s\n' "${web_tree_sha}" >"${backend_release}/.web-tree.sha256"
  printf '%s\n' "${WEB_TREE_FINGERPRINT_ALGORITHM}" \
    >"${backend_release}/.web-tree-fingerprint-algorithm"

  chown root:root \
    "${backend_release}/.source-commit" "${web_release}/.source-commit" \
    "${backend_release}/.release-id" "${web_release}/.release-id" \
    "${backend_release}/.core-api.sha256" \
    "${backend_release}/.migration-runner.sha256" \
    "${backend_release}/.web-index.sha256" \
    "${backend_release}/.web-tree.sha256" \
    "${backend_release}/.web-tree-fingerprint-algorithm"
  chmod 0444 \
    "${backend_release}/.source-commit" "${web_release}/.source-commit" \
    "${backend_release}/.release-id" "${web_release}/.release-id" \
    "${backend_release}/.core-api.sha256" \
    "${backend_release}/.migration-runner.sha256" \
    "${backend_release}/.web-index.sha256" \
    "${backend_release}/.web-tree.sha256" \
    "${backend_release}/.web-tree-fingerprint-algorithm"
}

main() {
  if test "$#" -ne 6; then
    printf '%s\n' \
      'Usage: 60-install-release.sh <release-id> <source-commit> <jar-sha256> <migration-runner-sha256> <index-sha256> <web-tree-sha256>' >&2
    exit 2
  fi
  test "$(id -u)" -eq 0 || {
    fail 'Complete release installation must run as root.'
    exit 1
  }

  local release_id="$1"
  local source_commit="$2"
  local expected_jar_sha="$3"
  local expected_runner_sha="$4"
  local expected_index_sha="$5"
  local expected_web_tree_sha="$6"
  local backend_release="${BACKEND_RELEASE_ROOT}/${release_id}"
  local web_release="${WEB_RELEASE_ROOT}/${release_id}"
  local staged_web_tree_sha

  [[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] &&
    [[ "${release_id}" != *'..'* ]] || {
    printf '%s\n' 'Release id must be 1-128 safe characters, start with an alphanumeric character, and not contain ...' >&2
    exit 2
  }
  [[ "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || {
    printf '%s\n' 'Source commit must be a lowercase 40-character Git SHA.' >&2
    exit 2
  }
  validate_sha256 'Core API JAR SHA-256' "${expected_jar_sha}" || exit $?
  validate_sha256 'Migration runner SHA-256' "${expected_runner_sha}" || exit $?
  validate_sha256 'Web index SHA-256' "${expected_index_sha}" || exit $?
  validate_sha256 'Web tree SHA-256' "${expected_web_tree_sha}" || exit $?

  validate_trusted_stage_tree
  verify_sha256 'staged Core API JAR' "${STAGE_ROOT}/core-api.jar" "${expected_jar_sha}"
  verify_sha256 'staged migration runner' "${STAGE_ROOT}/run-migration-once.sh" "${expected_runner_sha}"
  verify_sha256 'staged Web index' "${STAGE_ROOT}/web/index.html" "${expected_index_sha}"
  staged_web_tree_sha="$(read_web_tree_fingerprint "${STAGE_ROOT}/web" artifact)"
  test "${staged_web_tree_sha}" = "${expected_web_tree_sha}" ||
    fail "staged Web tree SHA-256 mismatch: expected=${expected_web_tree_sha} actual=${staged_web_tree_sha}"

  if ! grep -R --include='*.js' -Fq 'X-Hotel-AI-Authorization' "${STAGE_ROOT}/web/assets"; then
    fail 'Refusing to install a Web bundle without the Pilot bearer-login contract. Build with pnpm build:pilot.'
  fi

  if test -e /opt/hotel-ai-os/current && test ! -L /opt/hotel-ai-os/current; then
    fail '/opt/hotel-ai-os/current is not a symlink.'
  fi
  if test -e /srv/www/hotel-ai-os/current && test ! -L /srv/www/hotel-ai-os/current; then
    fail '/srv/www/hotel-ai-os/current is not a symlink.'
  fi
  if test -e "${backend_release}" || test -L "${backend_release}" ||
      test -e "${web_release}" || test -L "${web_release}"; then
    fail 'Release directory already exists.'
  fi

  if test -x "${FAST_RELEASE_COMMAND}"; then
    "${FAST_RELEASE_COMMAND}" BEGIN_FULL "${source_commit}" "${release_id}"
  else
    # The fixed command is absent only during the first-ever baseline bootstrap.
    # An already provisioned host must configure the coordinator before another
    # complete release so fast and full releases cannot overlap.
    if test -e /opt/hotel-ai-os/current || test -L /opt/hotel-ai-os/current ||
        test -e /srv/www/hotel-ai-os/current || test -L /srv/www/hotel-ai-os/current ||
        test -e /var/lib/hotel-ai-os-deploy || test -L /var/lib/hotel-ai-os-deploy; then
      fail 'FULL_RELEASE_COORDINATOR_REQUIRED Configure the fixed release command before updating an existing host.'
    fi
    printf 'INITIAL_FULL_BASELINE sourceCommit=%s\n' "${source_commit}"
  fi

  backend_candidate="${backend_release}"
  web_candidate="${web_release}"
  candidate_cleanup_armed=true
  trap cleanup_failed_candidate EXIT

  # Candidates remain root-only until every copied byte and metadata record is
  # verified. A failed verification therefore cannot expose copied content.
  install -d -o root -g root -m 0700 "${backend_release}"
  install -d -o root -g root -m 0700 "${web_release}"
  install -o root -g root -m 0600 "${STAGE_ROOT}/core-api.jar" "${backend_release}/core-api.jar"
  install -o root -g root -m 0700 \
    "${STAGE_ROOT}/run-migration-once.sh" \
    "${backend_release}/run-migration-once.sh"
  cp -a "${STAGE_ROOT}/web/." "${web_release}/"
  chown -R root:root "${web_release}"
  find "${web_release}" -type d -exec chmod 0700 {} +
  find "${web_release}" -type f -exec chmod 0600 {} +

  # Recompute every trusted identity from the copied release before it can
  # become active. This also detects a staged-tree mutation during the copy.
  verify_sha256 'installed Core API JAR' "${backend_release}/core-api.jar" "${expected_jar_sha}"
  verify_sha256 'installed migration runner' "${backend_release}/run-migration-once.sh" "${expected_runner_sha}"
  verify_sha256 'installed Web index' "${web_release}/index.html" "${expected_index_sha}"
  test "$(read_web_tree_fingerprint "${web_release}" artifact)" = "${expected_web_tree_sha}" ||
    fail 'installed Web tree SHA-256 differs from the verified staged artifact.'

  write_release_metadata \
    "${backend_release}" "${web_release}" "${source_commit}" "${release_id}" \
    "${expected_jar_sha}" "${expected_runner_sha}" "${expected_index_sha}" "${expected_web_tree_sha}"

  chmod 0444 "${backend_release}/core-api.jar"
  chmod 0555 "${backend_release}/run-migration-once.sh"
  find "${web_release}" -type f -exec chmod 0444 {} +
  find "${web_release}" -type d -exec chmod 0555 {} +
  chmod 0555 "${backend_release}"

  # From this point onward a partially switched pair must remain available for
  # the coordinator's explicit RESUME/ABORT recovery protocol.
  candidate_cleanup_armed=false
  ln -sfn "${backend_release}" /opt/hotel-ai-os/current
  ln -sfn "${web_release}" /srv/www/hotel-ai-os/current
  trap - EXIT

  printf '%s\n' 'RELEASE_INSTALL_COMPLETE'
  readlink -f /opt/hotel-ai-os/current
  readlink -f /srv/www/hotel-ai-os/current
  sha256sum /opt/hotel-ai-os/current/core-api.jar
  sha256sum /opt/hotel-ai-os/current/run-migration-once.sh
  sha256sum /srv/www/hotel-ai-os/current/index.html
  printf 'WEB_TREE_SHA256 %s\n' "$(read_web_tree_fingerprint /srv/www/hotel-ai-os/current installed)"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
