#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# Despite the historical filename, this is the complete production backup job:
# PostgreSQL, recoverable OTA/PMS state, 90-day encrypted raw evidence, and the
# read-only export consumed by the registered Windows backup device.

db_root=/var/backups/hotel-ai-os/postgres
db_daily=${db_root}/daily
db_monthly=${db_root}/monthly
db_yearly=${db_root}/yearly
state_root=/var/backups/sifangguan-ota/state
state_daily=${state_root}/daily
state_monthly=${state_root}/monthly
state_yearly=${state_root}/yearly
raw_backup_root=/var/backups/sifangguan-ota/raw/daily
stage_root=/var/backups/sifangguan-ota/staging
stage_state=${stage_root}/snapshot
stage_raw=${stage_root}/raw
source_state=/var/lib/sifangguan-ota
source_raw=${source_state}/analytics-retention/raw
runtime_env=/etc/sifangguan-ota/runtime.env
backup_key=/etc/hotel-ai-os/backup-encryption.key
export_root=/srv/sfg-backup-export
export_data=${export_root}/data
export_group=sfg-backup-pull
manifest_tool=/usr/local/lib/hotel-ai-os/publish-backup-manifest.py
raw_retention_days=90
timestamp="$(date '+%Y%m%dT%H%M%S%z')"
month_key="$(date '+%Y%m')"
year_key="$(date '+%Y')"

install -d -m 0700 -o root -g root \
  "${db_root}" "${db_daily}" "${db_monthly}" "${db_yearly}" \
  "${state_root}" "${state_daily}" "${state_monthly}" "${state_yearly}" \
  "${raw_backup_root}" "${stage_state}" "${stage_raw}" \
  "${stage_state}/var/lib/sifangguan-ota"

exec 9>/run/lock/hotel-ai-os-backup.lock
if ! flock -n 9; then
  printf '%s\n' 'BACKUP_ALREADY_RUNNING' >&2
  exit 75
fi

test "$(readlink -f "${db_root}")" = '/var/backups/hotel-ai-os/postgres'
test "$(readlink -f "${state_root}")" = '/var/backups/sifangguan-ota/state'
test "$(readlink -f "${stage_root}")" = '/var/backups/sifangguan-ota/staging'
test "$(readlink -f "${export_root}")" = '/srv/sfg-backup-export'
test -f "${backup_key}"
test "$(stat -c '%U:%a' "${backup_key}")" = 'root:600'
test -d "${source_state}"
test -r "${runtime_env}"
test -x "${manifest_tool}"
getent group "${export_group}" >/dev/null

db_plain="$(mktemp "${db_root}/.hotel_ai_os-XXXXXX.dump")"
db_check="$(mktemp "${db_root}/.hotel_ai_os-check-XXXXXX.dump")"
db_encrypted_tmp="${db_root}/.hotel_ai_os-${timestamp}.dump.enc"
db_encrypted_final="${db_daily}/hotel_ai_os-daily-${timestamp}.dump.enc"
state_encrypted_tmp="${state_root}/.sifangguan-ota-state-${timestamp}.tar.gz.enc"
state_encrypted_final="${state_daily}/sifangguan-ota-state-daily-${timestamp}.tar.gz.enc"
archive_list="$(mktemp "${state_root}/.archive-list-XXXXXX")"
raw_tmp=''
raw_list=''
quiesced=false
declare -a resume_services=()
declare -a resume_timers=()

resume_writers() {
  local resume_failed=false
  if ((${#resume_services[@]})); then
    systemctl start "${resume_services[@]}" || resume_failed=true
  fi
  if ((${#resume_timers[@]})); then
    systemctl start "${resume_timers[@]}" || resume_failed=true
  fi
  if [[ ${resume_failed} == true ]]; then
    printf '%s\n' 'BACKUP_WRITER_RESUME_FAILED' >&2
    return 1
  fi
  quiesced=false
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ ${quiesced} == true ]]; then
    resume_writers || status=1
  fi
  rm -f -- \
    "${db_plain}" "${db_check}" "${db_encrypted_tmp}" \
    "${state_encrypted_tmp}" "${archive_list}" \
    ${raw_tmp:+"${raw_tmp}"} ${raw_list:+"${raw_list}"}
  exit "${status}"
}
trap cleanup EXIT INT TERM

write_checksum() {
  local artifact=$1
  (cd "$(dirname "${artifact}")" && \
    sha256sum "$(basename "${artifact}")" >"$(basename "${artifact}.sha256")")
  chmod 0600 "${artifact}.sha256"
}

copy_tier_once() {
  local source=$1 target=$2
  if [[ ! -e ${target} ]]; then
    cp --reflink=auto --preserve=mode,timestamps -- "${source}" "${target}"
    write_checksum "${target}"
  fi
}

prune_tier() {
  local directory=$1 pattern=$2 keep=$3
  local files=()
  mapfile -t files < <(find "${directory}" -maxdepth 1 -type f -name "${pattern}" -printf '%f\n' | sort -r)
  local index
  for ((index = keep; index < ${#files[@]}; index++)); do
    rm -f -- \
      "${directory}/${files[index]}" \
      "${directory}/${files[index]}.sha256" \
      "${directory}/${files[index]}.source.sha256"
  done
}

encrypt_stream() {
  openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
    -pass "file:${backup_key}"
}

decrypt_stream() {
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
    -pass "file:${backup_key}"
}

# Preserve former-layout database backups in the exact-count daily tier.
shopt -s nullglob
for legacy_backup in "${db_root}"/hotel_ai_os-auto-*.dump.enc; do
  legacy_name="$(basename "${legacy_backup}")"
  legacy_target="${db_daily}/hotel_ai_os-daily-${legacy_name#hotel_ai_os-auto-}"
  if [[ ! -e ${legacy_target} ]]; then
    mv -- "${legacy_backup}" "${legacy_target}"
    write_checksum "${legacy_target}"
  fi
  rm -f -- "${legacy_backup}" "${legacy_backup}.sha256"
done

sudo -u postgres pg_dump \
  --format=custom \
  --compress=9 \
  --dbname hotel_ai_os \
  >"${db_plain}"

encrypt_stream <"${db_plain}" >"${db_encrypted_tmp}"
chmod 0600 "${db_encrypted_tmp}"
decrypt_stream <"${db_encrypted_tmp}" >"${db_check}"
cmp --silent "${db_plain}" "${db_check}"
sudo -u postgres pg_restore --list <"${db_check}" >/dev/null
mv -- "${db_encrypted_tmp}" "${db_encrypted_final}"
write_checksum "${db_encrypted_final}"
copy_tier_once \
  "${db_encrypted_final}" \
  "${db_monthly}/hotel_ai_os-monthly-${month_key}.dump.enc"
copy_tier_once \
  "${db_encrypted_final}" \
  "${db_yearly}/hotel_ai_os-yearly-${year_key}.dump.enc"

# First pass copies the bulk while writers remain online. Browser caches and
# sockets are replaceable runtime material; login/session stores are retained.
state_rsync_options=(
  -aHAX --numeric-ids --delete --delete-excluded --one-file-system
  --no-specials --no-devices
  --exclude='/analytics-retention/raw/***'
  --exclude='/**/Cache/***'
  --exclude='/**/Code Cache/***'
  --exclude='/**/GPUCache/***'
  --exclude='/**/GPUPersistentCache/***'
  --exclude='/**/GraphiteDawnCache/***'
  --exclude='/**/DawnGraphiteCache/***'
  --exclude='/**/DawnWebGPUCache/***'
  --exclude='/**/GrShaderCache/***'
  --exclude='/**/ShaderCache/***'
  --exclude='/**/Crashpad/***'
  --exclude='/**/BrowserMetrics*'
  --exclude='/**/DevToolsActivePort'
  --exclude='/**/Singleton*'
  --exclude='/.cache/***'
  --exclude='/.npm/***'
)
raw_rsync_options=(
  -a --delete --one-file-system
  --include='*/'
  --include='*.enc'
  --exclude='*'
)
rsync "${state_rsync_options[@]}" "${source_state}/" "${stage_state}/var/lib/sifangguan-ota/"
if [[ -d ${source_raw} ]]; then
  rsync "${raw_rsync_options[@]}" "${source_raw}/" "${stage_raw}/"
fi

writer_timers=(
  sifangguan-ota-analytics-import.timer
  sifangguan-wecom-onboarding.timer
  sifangguan-kpi-monthly-refresh.timer
)
writer_services=(
  hotel-ai-os-wecom-uat.service
  sifangguan-bieyanghong-browser.service
  sifangguan-ota-api.service
  sifangguan-ota-cloud.service
  sifangguan-ota-ctrip-pilot.service
  sifangguan-ota-analytics-import.service
  sifangguan-wecom-onboarding.service
  sifangguan-kpi-monthly-refresh.service
)
for unit in "${writer_timers[@]}"; do
  if systemctl is-active --quiet "${unit}"; then resume_timers+=("${unit}"); fi
done
for unit in "${writer_services[@]}"; do
  if systemctl is-active --quiet "${unit}"; then resume_services+=("${unit}"); fi
done
quiesced=true
if ((${#resume_timers[@]})); then systemctl stop "${resume_timers[@]}"; fi
if ((${#resume_services[@]})); then systemctl stop "${resume_services[@]}"; fi
rsync "${state_rsync_options[@]}" "${source_state}/" "${stage_state}/var/lib/sifangguan-ota/"
if [[ -d ${source_raw} ]]; then
  rsync "${raw_rsync_options[@]}" "${source_raw}/" "${stage_raw}/"
fi
install -d -m 0750 -o root -g root "${stage_state}/etc/sifangguan-ota"
install -m 0640 -o root -g root "${runtime_env}" "${stage_state}/etc/sifangguan-ota/runtime.env"
resume_writers

tar --acls --xattrs --numeric-owner -C "${stage_state}" -czf - etc var |
  encrypt_stream >"${state_encrypted_tmp}"
chmod 0600 "${state_encrypted_tmp}"
decrypt_stream <"${state_encrypted_tmp}" | tar -tzf - >"${archive_list}"
for required_entry in \
  etc/sifangguan-ota/runtime.env \
  var/lib/sifangguan-ota/report-sources.json \
  var/lib/sifangguan-ota/pms-login-secrets.json \
  var/lib/sifangguan-ota/live-report-snapshots.json \
  var/lib/sifangguan-ota/kpi-monthly-pms-summaries.json; do
  grep -Fxq -- "${required_entry}" "${archive_list}"
done
if grep -Fq -- 'var/lib/sifangguan-ota/analytics-retention/raw/' "${archive_list}"; then
  printf '%s\n' 'RAW_RETENTION_BOUNDARY_VIOLATION' >&2
  exit 1
fi
mv -- "${state_encrypted_tmp}" "${state_encrypted_final}"
write_checksum "${state_encrypted_final}"
copy_tier_once \
  "${state_encrypted_final}" \
  "${state_monthly}/sifangguan-ota-state-monthly-${month_key}.tar.gz.enc"
copy_tier_once \
  "${state_encrypted_final}" \
  "${state_yearly}/sifangguan-ota-state-yearly-${year_key}.tar.gz.enc"

# Each completed source day becomes one encrypted raw-evidence package. A
# source fingerprint rebuilds a day only when late files actually changed it.
for raw_day_dir in "${stage_raw}"/????-??-??; do
  [[ -d ${raw_day_dir} ]] || continue
  raw_day="$(basename "${raw_day_dir}")"
  [[ ${raw_day} =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || continue
  [[ ${raw_day} < "$(date '+%Y-%m-%d')" ]] || continue
  raw_target="${raw_backup_root}/analytics-raw-${raw_day}.tar.gz.enc"
  raw_fingerprint_file="${raw_target}.source.sha256"
  source_fingerprint="$(
    find "${raw_day_dir}" -xdev -type f -name '*.enc' \
      -printf '%P\0%s\0%T@\0' | sort -z | sha256sum | cut -d ' ' -f 1
  )"
  if [[ -s ${raw_target} && -s ${raw_fingerprint_file} ]] \
      && [[ $(<"${raw_fingerprint_file}") == "${source_fingerprint}" ]]; then
    continue
  fi
  if ! find "${raw_day_dir}" -xdev -type f -name '*.enc' -print -quit | grep -q .; then
    continue
  fi
  raw_tmp="${raw_backup_root}/.analytics-raw-${raw_day}.$$.tar.gz.enc"
  raw_list="$(mktemp "${raw_backup_root}/.raw-list-XXXXXX")"
  tar --numeric-owner -C "${stage_raw}" -czf - "${raw_day}" |
    encrypt_stream >"${raw_tmp}"
  chmod 0600 "${raw_tmp}"
  decrypt_stream <"${raw_tmp}" | tar -tzf - >"${raw_list}"
  grep -Eq "^${raw_day}/.+[.]enc$" "${raw_list}"
  rm -f -- "${raw_list}"
  raw_list=''
  mv -f -- "${raw_tmp}" "${raw_target}"
  raw_tmp=''
  write_checksum "${raw_target}"
  printf '%s\n' "${source_fingerprint}" >"${raw_fingerprint_file}"
  chmod 0600 "${raw_fingerprint_file}"
done

prune_tier "${db_daily}" 'hotel_ai_os-daily-*.dump.enc' 30
prune_tier "${db_monthly}" 'hotel_ai_os-monthly-*.dump.enc' 12
prune_tier "${db_yearly}" 'hotel_ai_os-yearly-*.dump.enc' 3
prune_tier "${state_daily}" 'sifangguan-ota-state-daily-*.tar.gz.enc' 30
prune_tier "${state_monthly}" 'sifangguan-ota-state-monthly-*.tar.gz.enc' 12
prune_tier "${state_yearly}" 'sifangguan-ota-state-yearly-*.tar.gz.enc' 3
prune_tier "${raw_backup_root}" 'analytics-raw-*.tar.gz.enc' "${raw_retention_days}"

if [[ -d ${source_raw} ]]; then
  find "${source_raw}" -xdev -type f -name '*.enc' \
    -mtime "+${raw_retention_days}" -delete
  find "${source_raw}" -xdev -depth -type d -empty -delete
fi

install -d -m 0755 -o root -g root "${export_root}"
for directory in \
  "${export_data}/hotel-ai-os/daily" \
  "${export_data}/hotel-ai-os/monthly" \
  "${export_data}/hotel-ai-os/yearly" \
  "${export_data}/sifangguan-ota/daily" \
  "${export_data}/sifangguan-ota/monthly" \
  "${export_data}/sifangguan-ota/yearly" \
  "${export_data}/analytics-raw/daily" \
  "${export_root}/receipts"; do
  install -d -m 0750 -o root -g "${export_group}" "${directory}"
done

publish_directory() {
  local source_directory=$1 source_pattern=$2 target_directory=$3
  local source target temporary existing base
  for source in "${source_directory}"/${source_pattern}; do
    [[ -f ${source} ]] || continue
    base="$(basename "${source}")"
    target="${target_directory}/${base}"
    if [[ ! -f ${target} || ! -f ${target}.sha256 ]] \
        || ! cmp --silent "${source}.sha256" "${target}.sha256" \
        || ! (cd "${target_directory}" && sha256sum --check "${base}.sha256" >/dev/null); then
      temporary="${target}.part.$$"
      install -m 0440 -o root -g "${export_group}" "${source}" "${temporary}"
      mv -f -- "${temporary}" "${target}"
      install -m 0440 -o root -g "${export_group}" "${source}.sha256" "${target}.sha256"
    fi
  done
  for existing in "${target_directory}"/${source_pattern}; do
    [[ -f ${existing} ]] || continue
    base="$(basename "${existing}")"
    if [[ ! -f ${source_directory}/${base} ]]; then
      rm -f -- "${existing}" "${existing}.sha256"
    fi
  done
}

publish_directory "${db_daily}" 'hotel_ai_os-daily-*.dump.enc' "${export_data}/hotel-ai-os/daily"
publish_directory "${db_monthly}" 'hotel_ai_os-monthly-*.dump.enc' "${export_data}/hotel-ai-os/monthly"
publish_directory "${db_yearly}" 'hotel_ai_os-yearly-*.dump.enc' "${export_data}/hotel-ai-os/yearly"
publish_directory "${state_daily}" 'sifangguan-ota-state-daily-*.tar.gz.enc' "${export_data}/sifangguan-ota/daily"
publish_directory "${state_monthly}" 'sifangguan-ota-state-monthly-*.tar.gz.enc' "${export_data}/sifangguan-ota/monthly"
publish_directory "${state_yearly}" 'sifangguan-ota-state-yearly-*.tar.gz.enc' "${export_data}/sifangguan-ota/yearly"
publish_directory "${raw_backup_root}" 'analytics-raw-*.tar.gz.enc' "${export_data}/analytics-raw/daily"

"${manifest_tool}" "${export_root}" "${export_group}"

rm -f -- "${db_plain}" "${db_check}" "${archive_list}"
trap - EXIT INT TERM
printf '%s\n' 'COMPLETE_BACKUP_EXPORT_OK'
printf 'retention=database:30/12/3,state:30/12/3,raw-days:%s export=%s\n' \
  "${raw_retention_days}" "${export_root}"
stat -c '%U:%G:%a:%s:%n' \
  "${db_encrypted_final}" "${state_encrypted_final}" "${export_root}/manifest-v1.json"
