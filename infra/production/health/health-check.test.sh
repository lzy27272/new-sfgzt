#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=health-check.sh
source "${script_dir}/health-check.sh"

fail() {
  printf 'not ok - %s\n' "$1" >&2
  exit 1
}

expect_failure() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    fail "${name}"
  fi
  printf 'ok - %s\n' "${name}"
}

expect_script_fragment() {
  local name="$1"
  local fragment="$2"
  if ! grep -Fq -- "${fragment}" "${script_dir}/health-check.sh"; then
    fail "${name}"
  fi
  printf 'ok - %s\n' "${name}"
}

valid_listing=$'META-INF/MANIFEST.MF\nBOOT-INF/classes/db/migration/V1__foundation.sql\nBOOT-INF/classes/db/migration/V9__notifications.sql\nBOOT-INF/classes/db/migration/V37__navigation.sql\nBOOT-INF/classes/db/migration/R__refresh_view.sql'
test "$(resolve_packaged_flyway_version "${valid_listing}")" = '37'
printf '%s\n' 'ok - highest packaged numeric migration is selected'

expected_state='1:101,9:-202,37:303'
validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|0|0'
printf '%s\n' 'ok - matching database state is accepted'

expect_failure 'missing intermediate migration is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,37:303|0|0'
expect_failure 'database ahead is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303,38:404|0|0'
expect_failure 'checksum drift is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-999,37:303|0|0'
expect_failure 'failed history row is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|1|0'
expect_failure 'non-numeric or checksum-less row is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|0|1'
expect_failure 'empty database state is rejected' \
  validate_flyway_database_state "${expected_state}" ''

duplicate_listing=$'BOOT-INF/classes/db/migration/V37__one.sql\nBOOT-INF/classes/db/migration/V37__two.sql'
expect_failure 'duplicate packaged version is rejected' resolve_packaged_flyway_version "${duplicate_listing}"

invalid_listing=$'BOOT-INF/classes/db/migration/V37_1__invalid.sql\nBOOT-INF/classes/db/migration/V37__valid.sql'
expect_failure 'invalid packaged version name is rejected' resolve_packaged_flyway_version "${invalid_listing}"

expect_failure 'JAR without versioned migrations is rejected' \
  resolve_packaged_flyway_version 'BOOT-INF/classes/db/migration/R__refresh_view.sql'

backup_fixture="$(mktemp -d "${TMPDIR:-/tmp}/hotel-ai-health-test.XXXXXXXX")"
cleanup_backup_fixture() {
  local resolved_fixture
  local resolved_temp
  resolved_fixture="$(realpath -- "${backup_fixture}")"
  resolved_temp="$(realpath -- "${TMPDIR:-/tmp}")"
  case "${resolved_fixture}" in
    "${resolved_temp}"/hotel-ai-health-test.*) rm -rf --one-file-system -- "${resolved_fixture}" ;;
    *) printf 'Refusing to remove unexpected test path: %s\n' "${resolved_fixture}" >&2 ;;
  esac
}
trap cleanup_backup_fixture EXIT
touch -t 202609160101 "${backup_fixture}/hotel_ai_os-daily-older.dump.enc"
touch -t 202609160102 "${backup_fixture}/hotel_ai_os-daily-newer.dump.enc"
test "$(select_latest_backup \
  "${backup_fixture}" \
  'hotel_ai_os-daily-*.dump.enc')" = \
  "${backup_fixture}/hotel_ai_os-daily-newer.dump.enc"
printf '%s\n' 'ok - latest backup selection is pipefail safe'

validate_running_release \
  '/opt/hotel-ai-os/releases/20260917-pilot8-example' \
  '1234' \
  '/opt/hotel-ai-os/releases/20260917-pilot8-example'
printf '%s\n' 'ok - running process from current immutable release is accepted'
expect_failure 'running process from stale release is rejected' \
  validate_running_release \
    '/opt/hotel-ai-os/releases/20260917-pilot8-current' \
    '1234' \
    '/opt/hotel-ai-os/releases/20260917-pilot8-stale'
expect_failure 'non-release current path is rejected' \
  validate_running_release '/opt/hotel-ai-os/current' '1234' '/opt/hotel-ai-os/current'
expect_failure 'invalid Core API PID is rejected' \
  validate_running_release \
    '/opt/hotel-ai-os/releases/20260917-pilot8-example' \
    '0' \
    '/opt/hotel-ai-os/releases/20260917-pilot8-example'

# These assertions deliberately protect the production gates that must not be
# weakened while replacing the former hard-coded Flyway version assertion.
expect_script_fragment 'required services gate is preserved' \
  'for service_name in ssh postgresql fail2ban clamav-freshclam hotel-ai-os-core-api; do'
expect_script_fragment 'Core API health endpoint gate is preserved' \
  'http://127.0.0.1:18080/actuator/health'
expect_script_fragment 'private PostgreSQL and API port gate is preserved' \
  "grep -E '(^0[.]0[.]0[.]0:5432"
expect_script_fragment 'root filesystem threshold gate is preserved' \
  'test "${root_usage}" -lt 85'
expect_script_fragment 'daily encrypted database backup gate is preserved' \
  '/var/backups/hotel-ai-os/postgres/daily'
expect_script_fragment 'daily database backup checksum gate is preserved' \
  "sha256sum --check \"\$(basename \"\${latest_backup}.sha256\")\""
expect_script_fragment 'offsite config ownership and mode gate is preserved' \
  "test \"\$(stat -c '%U:%a' /etc/hotel-ai-os/backup-offsite.env)\" = 'root:600'"
expect_script_fragment 'offsite remote filesystem allowlist is preserved' \
  'nfs|nfs4|cifs|fuse.rclone|fuse.sshfs) ;;'
expect_script_fragment 'offsite database backup gate is preserved' \
  'offsite_latest="${HOTEL_AI_OS_BACKUP_OFFSITE_DIR}/postgres/daily/'
expect_script_fragment 'OTA runtime config backup gate is preserved' \
  "if [[ -r /etc/sifangguan-ota/runtime.env ]]; then"
expect_script_fragment 'OTA runtime offsite integrity gate is preserved' \
  'sha256sum --check "$(basename "${offsite_runtime}.sha256")"'

if grep -Fq -- "test \"\${flyway_state}\" = '22|true'" "${script_dir}/health-check.sh"; then
  fail 'hard-coded Flyway V22 assertion is removed'
fi
printf '%s\n' 'ok - hard-coded Flyway V22 assertion is removed'

printf '%s\n' 'HEALTH_CHECK_MIGRATION_GATE_TESTS_OK'
