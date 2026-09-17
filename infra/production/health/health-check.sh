#!/usr/bin/env bash
set -Eeuo pipefail

resolve_packaged_flyway_version() {
  local jar_listing="${1-}"
  local valid_versions

  if ! valid_versions="$(resolve_packaged_flyway_versions "${jar_listing}")"; then
    return 1
  fi
  printf '%s\n' "$(printf '%s\n' "${valid_versions}" | tail -n 1)"
}

resolve_packaged_flyway_versions() {
  local jar_listing="${1-}"
  local candidate_count
  local valid_versions
  local valid_count
  local duplicate_versions

  candidate_count="$(printf '%s\n' "${jar_listing}" |
    grep -Ec '^BOOT-INF/classes/db/migration/V.*[.]sql$' || true)"
  valid_versions="$(printf '%s\n' "${jar_listing}" |
    sed -nE 's#^BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[^/]+[.]sql$#\1#p')"
  valid_count="$(printf '%s\n' "${valid_versions}" | awk 'NF { count += 1 } END { print count + 0 }')"

  if test "${candidate_count}" -eq 0 || test "${candidate_count}" -ne "${valid_count}"; then
    printf '%s\n' 'Core API JAR has no valid numeric Flyway migrations or contains an invalid V migration name.' >&2
    return 1
  fi

  duplicate_versions="$(printf '%s\n' "${valid_versions}" | sort -n | uniq -d)"
  if test -n "${duplicate_versions}"; then
    printf 'Core API JAR contains duplicate Flyway versions: %s\n' "${duplicate_versions}" >&2
    return 1
  fi

  printf '%s\n' "${valid_versions}" | sort -n
}

read_packaged_flyway_state() {
  local jar_path="${1:?Core API JAR path is required}"
  test -r "${jar_path}"
  command -v python3 >/dev/null
  python3 - "${jar_path}" <<'PY'
import re
import sys
import zipfile
import zlib

jar_path = sys.argv[1]
candidate_pattern = re.compile(r"BOOT-INF/classes/db/migration/V.*[.]sql")
valid_pattern = re.compile(
    r"BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[^/]+[.]sql"
)

with zipfile.ZipFile(jar_path) as archive:
    candidates = sorted(
        name for name in archive.namelist() if candidate_pattern.fullmatch(name)
    )
    migrations = []
    for name in candidates:
        match = valid_pattern.fullmatch(name)
        if match is None:
            raise SystemExit(
                "Core API JAR contains an invalid numeric Flyway migration name: " + name
            )
        migrations.append((int(match.group(1)), name))
    if not migrations:
        raise SystemExit("Core API JAR has no valid numeric Flyway migrations.")
    versions = [version for version, _ in migrations]
    if len(versions) != len(set(versions)):
        raise SystemExit("Core API JAR contains duplicate Flyway versions.")

    state = []
    for version, name in sorted(migrations):
        try:
            text = archive.read(name).decode("utf-8")
        except UnicodeDecodeError as exception:
            raise SystemExit(f"Flyway migration is not valid UTF-8: {name}") from exception
        lines = re.split(r"\r\n|\r|\n", text)
        if text.endswith(("\r", "\n")):
            lines.pop()
        if lines and lines[0].startswith("\ufeff"):
            lines[0] = lines[0][1:]
        checksum = 0
        for line in lines:
            checksum = zlib.crc32(line.encode("utf-8"), checksum)
        if checksum >= 2**31:
            checksum -= 2**32
        state.append(f"{version}:{checksum}")

print(",".join(state))
PY
}

validate_flyway_database_state() {
  local expected_state="${1:?Expected Flyway state is required}"
  local database_state="${2-}"
  local database_migrations
  local failed_count
  local invalid_history_count
  local extra

  IFS='|' read -r database_migrations failed_count invalid_history_count extra <<<"${database_state}"
  if [[ ! "${expected_state}" =~ ^[1-9][0-9]*:-?[0-9]+(,[1-9][0-9]*:-?[0-9]+)*$ \
      || ! "${database_migrations}" =~ ^[1-9][0-9]*:-?[0-9]+(,[1-9][0-9]*:-?[0-9]+)*$ \
      || ! "${failed_count}" =~ ^[0-9]+$ \
      || ! "${invalid_history_count}" =~ ^[0-9]+$ \
      || -n "${extra-}" ]]; then
    printf 'Invalid Flyway database health state: %s\n' "${database_state}" >&2
    return 1
  fi
  if test "${failed_count}" -ne 0 || test "${invalid_history_count}" -ne 0; then
    printf 'Flyway history contains failed, non-numeric, or checksum-less versioned migrations: %s\n' "${database_state}" >&2
    return 1
  fi
  if test "${database_migrations}" != "${expected_state}"; then
    printf 'Flyway migration history mismatch: jar=%s database=%s\n' \
      "${expected_state}" "${database_migrations}" >&2
    return 1
  fi
}

select_latest_backup() {
  local backup_directory="${1:?Backup directory is required}"
  local latest_line

  # Do not use `head` in this pipe: with `set -o pipefail`, a sufficiently
  # large directory can make the upstream sorter receive SIGPIPE and turn a
  # healthy deployment into a false rollback.
  latest_line="$(find "${backup_directory}" \
    -maxdepth 1 -type f -name 'hotel_ai_os-auto-*.dump.enc' \
    -printf '%T@ %p\n' | sort -nr | sed -n '1p')"
  test -n "${latest_line}"
  printf '%s\n' "${latest_line#* }"
}

main() {
  local service_name
  local current_release
  local core_api_jar
  local main_pid
  local running_release
  local packaged_flyway_state
  local packaged_flyway_version
  local flyway_state
  local database_flyway_state
  local database_flyway_version
  local root_usage
  local latest_backup

  for service_name in ssh postgresql fail2ban clamav-freshclam hotel-ai-os-core-api; do
    test "$(systemctl is-active "${service_name}")" = 'active'
  done

  curl --fail --silent --show-error \
    http://127.0.0.1:18080/actuator/health >/dev/null

  current_release="$(readlink -f /opt/hotel-ai-os/current)"
  test -n "${current_release}"
  core_api_jar="${current_release}/core-api.jar"
  packaged_flyway_state="$(read_packaged_flyway_state "${core_api_jar}")"
  packaged_flyway_version="${packaged_flyway_state##*,}"
  packaged_flyway_version="${packaged_flyway_version%%:*}"

  main_pid="$(systemctl show hotel-ai-os-core-api.service --property MainPID --value)"
  [[ "${main_pid}" =~ ^[1-9][0-9]*$ ]]
  running_release="$(readlink -f "/proc/${main_pid}/cwd")"
  test "${running_release}" = "${current_release}"

  flyway_state="$(sudo -u postgres psql \
    --dbname hotel_ai_os \
    --tuples-only \
    --no-align \
    --set=ON_ERROR_STOP=1 \
    --command "
      select coalesce(
                 string_agg(
                   version || ':' || checksum::text,
                   ',' order by case
                     when version ~ '^[1-9][0-9]*$' then version::bigint
                   end
                 ) filter (
                   where success
                     and version ~ '^[1-9][0-9]*$'
                     and checksum is not null
                 ),
                 ''
             )
             || '|' || count(*) filter (where success = false)
             || '|' || count(*) filter (
                            where version is not null
                              and (
                                version !~ '^[1-9][0-9]*$'
                                or checksum is null
                              )
                        )
        from flyway_schema_history
    ")"
  validate_flyway_database_state "${packaged_flyway_state}" "${flyway_state}"
  database_flyway_state="${flyway_state%%|*}"
  database_flyway_version="${database_flyway_state##*,}"
  database_flyway_version="${database_flyway_version%%:*}"

  if ss -lnt | awk 'NR > 1 {print $4}' |
      grep -E '(^0[.]0[.]0[.]0:5432$|^\[::\]:5432$|^0[.]0[.]0[.]0:18080$|^\[::\]:18080$)' \
      >/dev/null; then
    printf '%s\n' 'Private database or API port is exposed publicly.' >&2
    exit 1
  fi

  root_usage="$(df --output=pcent / | tail -n 1 | tr -dc '0-9')"
  test -n "${root_usage}"
  test "${root_usage}" -lt 85

  latest_backup="$(select_latest_backup /var/backups/hotel-ai-os/postgres)"
  test -n "${latest_backup}"
  find "${latest_backup}" -mmin -1560 -print -quit | grep -q .
  sha256sum --check "${latest_backup}.sha256" >/dev/null

  printf 'HOTEL_AI_OS_HEALTH_OK flyway_jar=%s flyway_db=%s failed_migrations=0 root_usage=%s%% backup=%s\n' \
    "${packaged_flyway_version}" "${database_flyway_version}" "${root_usage}" "$(basename "${latest_backup}")"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
