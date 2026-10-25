#!/usr/bin/env bash
set -Eeuo pipefail

stage=/tmp/hotel-ai-os-release
for helper in core-originals-manifest.py core-state-snapshots.py rehearse-core-originals-restore.sh; do
  test -f "${stage}/${helper}" && test ! -L "${stage}/${helper}"
done
install -d -o root -g root -m 0755 /usr/local/lib/hotel-ai-os
for helper in core-originals-manifest.py core-state-snapshots.py rehearse-core-originals-restore.sh; do
  install -o root -g root -m 0750 "${stage}/${helper}" "/usr/local/lib/hotel-ai-os/${helper}"
done
install -o root -g root -m 0640 "${stage}/core-originals-reference.sql" /usr/local/lib/hotel-ai-os/core-originals-reference.sql
install -o root -g root -m 0750 \
  "${stage}/backup-postgres.sh" \
  /usr/local/lib/hotel-ai-os/backup-postgres.sh
install -o root -g root -m 0750 \
  "${stage}/publish-backup-manifest.py" \
  /usr/local/lib/hotel-ai-os/publish-backup-manifest.py
install -o root -g root -m 0750 \
  "${stage}/configure-backup-pull.sh" \
  /usr/local/lib/hotel-ai-os/configure-backup-pull.sh
install -o root -g root -m 0750 \
  "${stage}/health-check.sh" \
  /usr/local/lib/hotel-ai-os/health-check.sh

for unit_name in \
  hotel-ai-os-postgres-backup.service \
  hotel-ai-os-postgres-backup.timer \
  hotel-ai-os-health-check.service \
  hotel-ai-os-health-check.timer; do
  install -o root -g root -m 0644 \
    "${stage}/${unit_name}" \
    "/etc/systemd/system/${unit_name}"
done

bash -n /usr/local/lib/hotel-ai-os/backup-postgres.sh
bash -n /usr/local/lib/hotel-ai-os/configure-backup-pull.sh
python3 -m py_compile /usr/local/lib/hotel-ai-os/publish-backup-manifest.py
python3 -m py_compile /usr/local/lib/hotel-ai-os/core-originals-manifest.py
python3 -m py_compile /usr/local/lib/hotel-ai-os/core-state-snapshots.py
bash -n /usr/local/lib/hotel-ai-os/rehearse-core-originals-restore.sh
bash -n /usr/local/lib/hotel-ai-os/health-check.sh
systemd-analyze verify \
  /etc/systemd/system/hotel-ai-os-postgres-backup.service \
  /etc/systemd/system/hotel-ai-os-postgres-backup.timer \
  /etc/systemd/system/hotel-ai-os-health-check.service \
  /etc/systemd/system/hotel-ai-os-health-check.timer
systemd-analyze calendar '*-*-* 03:10:00 Asia/Shanghai' >/dev/null
systemd-analyze calendar '*-*-* 03:35:00 Asia/Shanghai' >/dev/null

systemctl daemon-reload
systemctl start hotel-ai-os-postgres-backup.service
systemctl start hotel-ai-os-health-check.service
systemctl enable --now \
  hotel-ai-os-postgres-backup.timer \
  hotel-ai-os-health-check.timer

printf '%s\n' 'OPERATIONS_AUTOMATION_OK'
systemctl show hotel-ai-os-postgres-backup.service \
  -p Result -p ExecMainStatus
systemctl show hotel-ai-os-health-check.service \
  -p Result -p ExecMainStatus
systemctl list-timers \
  hotel-ai-os-postgres-backup.timer \
  hotel-ai-os-health-check.timer \
  --no-pager
