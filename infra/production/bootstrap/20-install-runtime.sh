#!/usr/bin/env bash
set -Eeuo pipefail

export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a
export APT_LISTCHANGES_FRONTEND=none

apt-get update
apt-get -y -o Dpkg::Options::=--force-confold upgrade
apt-get -y install \
  acl \
  ca-certificates \
  clamav \
  clamav-freshclam \
  curl \
  fail2ban \
  fonts-noto-cjk \
  fonts-wqy-zenhei \
  gnupg \
  jq \
  logrotate \
  openjdk-21-jre-headless \
  postgresql \
  postgresql-contrib \
  python3 \
  rsync \
  unzip

systemctl enable --now postgresql
systemctl enable --now fail2ban
systemctl enable --now clamav-freshclam

printf '%s\n' 'RUNTIME_INSTALL_COMPLETE'
java -version
psql --version
fail2ban-client version
clamscan --version
