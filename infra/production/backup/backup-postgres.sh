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
core_env=/etc/hotel-ai-os/core-api.env
source_core=/var/lib/hotel-ai-os/attachments
stage_core=${stage_state}/var/lib/hotel-ai-os/attachments
original_manifest=${stage_state}/var/lib/hotel-ai-os/backup-core-originals-manifest.json
original_tool=/usr/local/lib/hotel-ai-os/core-originals-manifest.py
snapshot_tool=/usr/local/lib/hotel-ai-os/core-state-snapshots.py
reference_sql=/usr/local/lib/hotel-ai-os/core-originals-reference.sql
backup_key=/etc/hotel-ai-os/backup-encryption.key
export_root=/srv/sfg-backup-export
export_data=${export_root}/data
export_group=sfg-backup-pull
manifest_tool=/usr/local/lib/hotel-ai-os/publish-backup-manifest.py
raw_retention_days=90
timestamp="$(date '+%Y%m%dT%H%M%S%z')"
month_key="$(date '+%Y%m')"
year_key="$(date '+%Y')"
backup_today="${timestamp:0:4}-${timestamp:4:2}-${timestamp:6:2}"

install -d -m 0700 -o root -g root \
  "${db_root}" "${db_daily}" "${db_monthly}" "${db_yearly}" \
  "${state_root}" "${state_daily}" "${state_monthly}" "${state_yearly}" \
  "${raw_backup_root}" "${stage_state}" "${stage_raw}" "${stage_core}" \
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
test -x "${original_tool}"
test -x "${snapshot_tool}"
test -r "${reference_sql}"
test -r "${core_env}"
getent group "${export_group}" >/dev/null

# One set of copy rules serves actual rsync and both capacity measurements.
# Cookies/login stores remain included; only regenerable browser caches are excluded.
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
core_rsync_options=(-aHAX --numeric-ids --delete --one-file-system --no-specials --no-devices)
raw_rsync_options=(-a --delete --one-file-system --include='*/' --include='*.enc' --exclude='*')

# Identical validated reuse condition for estimation and the actual archive loop.
raw_cache_reusable() {
  local raw_day_dir=$1 raw_day=$2 raw_fingerprint_file="${raw_backup_root}/.analytics-raw-$2.source.sha256"
  source_fingerprint="$(find "${raw_day_dir}" -xdev -type f -name '*.enc' -printf '%P\0%s\0%T@\0' | sort -z | sha256sum | cut -d ' ' -f 1)" || exit 1
  [[ -s ${raw_fingerprint_file} ]] || return 1
  read -r cached_fingerprint cached_raw_name <"${raw_fingerprint_file}"
  [[ ${cached_fingerprint} == "${source_fingerprint}" && ${cached_raw_name} =~ ^analytics-raw-${raw_day}-[0-9a-f]{64}[.]tar[.]gz[.]enc$ \
     && -s ${raw_backup_root}/${cached_raw_name} && -s ${raw_backup_root}/${cached_raw_name}.sha256 ]] \
    && (cd "${raw_backup_root}" && sha256sum --check "${cached_raw_name}.sha256" >/dev/null)
}

check_backup_capacity() (
  local source_bytes database_bytes raw_bytes completed_archives=0 rebuild_archives=0 measurement_root raw_day_dir raw_day
  measurement_root="$(mktemp -d "${stage_root}/.capacity-XXXXXXXX")"
  shopt -s nullglob
  # Only files created by this measurement are removed, never backup/source data.
  trap 'rm -f -- "${measurement_root}"/*.lengths "${measurement_root}/measurement.json"; rmdir -- "${measurement_root}"/fresh-* "${measurement_root}"' EXIT
  install -d -m 0700 "${measurement_root}/fresh-state" "${measurement_root}/fresh-core" "${measurement_root}/fresh-raw"
  : >"${measurement_root}/raw.lengths"
  : >"${measurement_root}/completed.lengths"
  : >"${measurement_root}/rebuild.lengths"
  : >"${measurement_root}/pending.lengths"
  # Fresh, empty dry-run destinations enumerate the complete selected
  # source independently of existing staging. %o forces even deletions through
  # the machine format; existing directories suppress human mkdir messages.
  LC_ALL=C rsync "${state_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
    "${source_state}/" "${measurement_root}/fresh-state/" >"${measurement_root}/state.lengths"
  LC_ALL=C rsync "${core_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
    "${source_core}/" "${measurement_root}/fresh-core/" >"${measurement_root}/core.lengths"
  LC_ALL=C rsync "${state_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
    "${source_state}/" "${stage_state}/var/lib/sifangguan-ota/" >>"${measurement_root}/pending.lengths"
  LC_ALL=C rsync "${core_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
    "${source_core}/" "${stage_core}/" >>"${measurement_root}/pending.lengths"
  if [[ -d ${source_raw} ]]; then
    LC_ALL=C rsync "${raw_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
      "${source_raw}/" "${measurement_root}/fresh-raw/" >"${measurement_root}/raw.lengths"
    LC_ALL=C rsync "${raw_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
      "${source_raw}/" "${stage_raw}/" >>"${measurement_root}/pending.lengths"
    for raw_day_dir in "${source_raw}"/????-??-??; do
      [[ -d ${raw_day_dir} ]] || continue
      raw_day="$(basename "${raw_day_dir}")"
      [[ ${raw_day} =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ && ${raw_day} < ${backup_today} ]] || continue
      install -d -m 0700 "${measurement_root}/fresh-${raw_day}"
      LC_ALL=C rsync "${raw_rsync_options[@]}" --dry-run --info=NONE,NAME1 --out-format='%o %l' \
        "${raw_day_dir}/" "${measurement_root}/fresh-${raw_day}/" >"${measurement_root}/${raw_day}.lengths"
      cat "${measurement_root}/${raw_day}.lengths" >>"${measurement_root}/completed.lengths"
      completed_archives=$((completed_archives + 1))
      if ! raw_cache_reusable "${raw_day_dir}" "${raw_day}"; then
        cat "${measurement_root}/${raw_day}.lengths" >>"${measurement_root}/rebuild.lengths"
        rebuild_archives=$((rebuild_archives + 1))
      fi
    done
  fi
  "${snapshot_tool}" measure --state-lengths "${measurement_root}/state.lengths" \
    --core-lengths "${measurement_root}/core.lengths" --raw-lengths "${measurement_root}/raw.lengths" \
    --completed-lengths "${measurement_root}/completed.lengths" --pending-lengths "${measurement_root}/pending.lengths" \
    --completed-archives "${completed_archives}" --rebuild-lengths "${measurement_root}/rebuild.lengths" --rebuild-archives "${rebuild_archives}" \
    --raw-backup-root "${raw_backup_root}" --raw-export-root "${export_data}/analytics-raw/daily" >"${measurement_root}/measurement.json"
  read -r source_bytes raw_bytes < <(python3 -I - "${measurement_root}/measurement.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1],encoding='ascii'));print(m['stateBytes'],m['rawBytes'])
PY
  )
  database_bytes="$(sudo -u postgres psql -XAtq --set ON_ERROR_STOP=1 --dbname hotel_ai_os \
    --command "select pg_database_size(current_database())")"
  [[ ${source_bytes} =~ ^[1-9][0-9]*$ && ${database_bytes} =~ ^[1-9][0-9]*$ ]]
  "${snapshot_tool}" capacity --state-root "${state_root}" --database-root "${db_root}" \
    --stage-root "${stage_root}" --export-root "${export_root}" \
    --state-bytes "${source_bytes}" --database-bytes "${database_bytes}" \
    --raw-backup-root "${raw_backup_root}" --raw-bytes "${raw_bytes}" --measurements "${measurement_root}/measurement.json"
)
# Fail before allocating the first bulk staging copy, and recheck immediately
# before downtime. Insufficient capacity never triggers retention pruning.
check_backup_capacity

db_plain="$(mktemp "${db_root}/.hotel_ai_os-XXXXXX.dump")"
db_check="$(mktemp "${db_root}/.hotel_ai_os-check-XXXXXX.dump")"
db_encrypted_tmp="${db_root}/.hotel_ai_os-${timestamp}.dump.enc"
db_encrypted_final="${db_daily}/hotel_ai_os-daily-${timestamp}.dump.enc"
state_encrypted_tmp="${state_root}/.sifangguan-ota-state-${timestamp}.snapshot.json.enc"
state_encrypted_final="${state_daily}/sifangguan-ota-state-daily-${timestamp}.snapshot.json.enc"
archive_list="$(mktemp "${state_root}/.archive-list-XXXXXX")"
original_settings="$(mktemp "${state_root}/.core-original-settings-XXXXXX")"
original_references="$(mktemp "${state_root}/.core-original-references-XXXXXX")"
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
    "${state_encrypted_tmp}" "${archive_list}" "${original_settings}" "${original_references}" \
    ${raw_tmp:+"${raw_tmp}"} ${raw_list:+"${raw_list}"}
  exit "${status}"
}
trap cleanup EXIT INT TERM
core_pid="$(systemctl show hotel-ai-os-core-api.service -p MainPID --value)"
[[ "${core_pid}" =~ ^[0-9]+$ ]]
"${original_tool}" settings "${core_env}" "${core_pid}" >"${original_settings}"
install -d -m 0700 -o root -g root "${stage_core}"
test "$(readlink -f "${stage_core}")" = '/var/backups/sifangguan-ota/staging/snapshot/var/lib/hotel-ai-os/attachments'

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


# First pass copies the bulk while writers remain online. Browser caches and
# sockets are replaceable runtime material; login/session stores are retained.
rsync "${state_rsync_options[@]}" "${source_state}/" "${stage_state}/var/lib/sifangguan-ota/"
rsync "${core_rsync_options[@]}" "${source_core}/" "${stage_core}/"
if [[ -d ${source_raw} ]]; then
  rsync "${raw_rsync_options[@]}" "${source_raw}/" "${stage_raw}/"
fi

writer_timers=(
  sifangguan-ota-analytics-import.timer
  sifangguan-wecom-onboarding.timer
  sifangguan-kpi-monthly-refresh.timer
)
writer_services=(
  hotel-ai-os-core-api.service
  hotel-ai-os-wecom-uat.service
  sifangguan-bieyanghong-browser.service
  sifangguan-ota-api.service
  sifangguan-ota-cloud.service
  sifangguan-ota-ctrip-pilot.service
  sifangguan-ota-analytics-import.service
  sifangguan-wecom-onboarding.service
  sifangguan-kpi-monthly-refresh.service
)
check_backup_capacity
for unit in "${writer_timers[@]}"; do
  if systemctl is-active --quiet "${unit}"; then resume_timers+=("${unit}"); fi
done
for unit in "${writer_services[@]}"; do
  if systemctl is-active --quiet "${unit}"; then resume_services+=("${unit}"); fi
done
quiesced=true
if ((${#resume_timers[@]})); then systemctl stop "${resume_timers[@]}"; fi
if ((${#resume_services[@]})); then systemctl stop "${resume_services[@]}"; fi
sudo -u postgres pg_dump --format=custom --compress=9 --dbname hotel_ai_os >"${db_plain}"
rsync "${state_rsync_options[@]}" "${source_state}/" "${stage_state}/var/lib/sifangguan-ota/"
rsync "${core_rsync_options[@]}" "${source_core}/" "${stage_core}/"
sudo -u postgres psql -XAtq --dbname hotel_ai_os <"${reference_sql}" >"${original_references}"
"${original_tool}" capture "${stage_core}" "${original_manifest}" "${timestamp}" "${original_settings}" "${original_references}" >/dev/null
if [[ -d ${source_raw} ]]; then
  rsync "${raw_rsync_options[@]}" "${source_raw}/" "${stage_raw}/"
fi
install -d -m 0750 -o root -g root "${stage_state}/etc/sifangguan-ota"
install -m 0640 -o root -g root "${runtime_env}" "${stage_state}/etc/sifangguan-ota/runtime.env"
check_backup_capacity
resume_writers

encrypt_stream <"${db_plain}" >"${db_encrypted_tmp}"
chmod 0600 "${db_encrypted_tmp}"
decrypt_stream <"${db_encrypted_tmp}" >"${db_check}"
cmp --silent "${db_plain}" "${db_check}"
sudo -u postgres pg_restore --list <"${db_check}" >/dev/null
mv -- "${db_encrypted_tmp}" "${db_encrypted_final}"
write_checksum "${db_encrypted_final}"

"${snapshot_tool}" create --source "${stage_state}" --state-root "${state_root}" \
  --index "${state_encrypted_final}" --snapshot "${timestamp}" \
  --key "${backup_key}" --database "${db_encrypted_final}"
# The first legacy OR incremental monthly/yearly pair is preserved. Promotion
# uses the existing cp/reflink semantics and repairs interrupted half-pairs only
# from an index/dump with the exact same frozen ciphertext digest.
"${snapshot_tool}" promote --state-root "${state_root}" --database-root "${db_root}" \
  --key "${backup_key}" --snapshot "${timestamp}"

# Each completed source day becomes one encrypted raw-evidence package. A
# source fingerprint rebuilds a day only when late files actually changed it.
for raw_day_dir in "${stage_raw}"/????-??-??; do
  [[ -d ${raw_day_dir} ]] || continue
  raw_day="$(basename "${raw_day_dir}")"
  [[ ${raw_day} =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || continue
  [[ ${raw_day} < ${backup_today} ]] || continue
  if raw_cache_reusable "${raw_day_dir}" "${raw_day}"; then continue; fi
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
  # Late source files create an immutable revision, never overwrite a package
  # referenced by a device's previous atomic accepted bundle.
  "${snapshot_tool}" commit-raw --raw-root "${raw_backup_root}" --source-temporary "${raw_tmp}" \
    --day "${raw_day}" --fingerprint "${source_fingerprint}"
  raw_tmp=''
done

"${snapshot_tool}" prune --state-root "${state_root}" --database-root "${db_root}" --key "${backup_key}"
"${snapshot_tool}" prune-raw --raw-root "${raw_backup_root}" --keep "${raw_retention_days}"

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
  "${export_data}/sifangguan-ota/objects" \
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
  # Stale export files are removed only AFTER the new complete manifest is
  # atomically published. A failed copy/publish keeps the previous closure.
}

publish_directory "${db_daily}" 'hotel_ai_os-daily-*.dump.enc' "${export_data}/hotel-ai-os/daily"
publish_directory "${db_monthly}" 'hotel_ai_os-monthly-*.dump.enc' "${export_data}/hotel-ai-os/monthly"
publish_directory "${db_yearly}" 'hotel_ai_os-yearly-*.dump.enc' "${export_data}/hotel-ai-os/yearly"
publish_directory "${state_daily}" 'sifangguan-ota-state-daily-*.tar.gz.enc' "${export_data}/sifangguan-ota/daily"
publish_directory "${state_monthly}" 'sifangguan-ota-state-monthly-*.tar.gz.enc' "${export_data}/sifangguan-ota/monthly"
publish_directory "${state_yearly}" 'sifangguan-ota-state-yearly-*.tar.gz.enc' "${export_data}/sifangguan-ota/yearly"
publish_directory "${state_daily}" 'sifangguan-ota-state-daily-*.snapshot.json.enc' "${export_data}/sifangguan-ota/daily"
publish_directory "${state_monthly}" 'sifangguan-ota-state-monthly-*.snapshot.json.enc' "${export_data}/sifangguan-ota/monthly"
publish_directory "${state_yearly}" 'sifangguan-ota-state-yearly-*.snapshot.json.enc' "${export_data}/sifangguan-ota/yearly"
publish_directory "${state_root}/objects" '*.pack.enc' "${export_data}/sifangguan-ota/objects"
publish_directory "${raw_backup_root}" 'analytics-raw-*.tar.gz.enc' "${export_data}/analytics-raw/daily"

"${snapshot_tool}" export-closure --state-root "${state_root}" --database-root "${db_root}" \
  --raw-root "${raw_backup_root}" --key "${backup_key}" --output "${export_root}/.recovery-closure.json"
"${manifest_tool}" "${export_root}" "${export_group}"

rm -f -- "${db_plain}" "${db_check}" "${archive_list}" "${original_settings}" "${original_references}"
trap - EXIT INT TERM
printf '%s\n' 'COMPLETE_BACKUP_EXPORT_OK'
printf 'pairedOriginalsSnapshot=%s type=CORE_AND_OTA_STATE_WITH_ORIGINALS\n' "${timestamp}"
printf 'retention=database:30/12/3,state:30/12/3,raw-days:%s export=%s\n' \
  "${raw_retention_days}" "${export_root}"
stat -c '%U:%G:%a:%s:%n' \
  "${db_encrypted_final}" "${state_encrypted_final}" "${export_root}/manifest-v1.json"
