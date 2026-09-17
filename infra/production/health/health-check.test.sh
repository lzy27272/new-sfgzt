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
touch -t 202609160101 "${backup_fixture}/hotel_ai_os-auto-older.dump.enc"
touch -t 202609160102 "${backup_fixture}/hotel_ai_os-auto-newer.dump.enc"
test "$(select_latest_backup "${backup_fixture}")" = \
  "${backup_fixture}/hotel_ai_os-auto-newer.dump.enc"
printf '%s\n' 'ok - latest backup selection is pipefail safe'

printf '%s\n' 'HEALTH_CHECK_MIGRATION_GATE_TESTS_OK'
