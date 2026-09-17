#!/usr/bin/env bash
set -Eeuo pipefail

if test "$#" -ne 3; then
  printf '%s\n' 'Usage: 60-install-release.sh <release-id> <jar-sha256> <index-sha256>' >&2
  exit 2
fi

release_id="$1"
expected_jar_sha="$2"
expected_index_sha="$3"
stage_root=/tmp/hotel-ai-os-release
backend_release="/opt/hotel-ai-os/releases/${release_id}"
web_release="/srv/www/hotel-ai-os/releases/${release_id}"

case "${release_id}" in
  *[!a-zA-Z0-9._-]*|'')
    printf '%s\n' 'Invalid release id.' >&2
    exit 2
    ;;
esac

actual_jar_sha="$(sha256sum "${stage_root}/core-api.jar" | awk '{print $1}')"
actual_index_sha="$(sha256sum "${stage_root}/web/index.html" | awk '{print $1}')"
test "${actual_jar_sha}" = "${expected_jar_sha}"
test "${actual_index_sha}" = "${expected_index_sha}"

if ! grep -R --include='*.js' -Fq 'X-Hotel-AI-Authorization' "${stage_root}/web/assets"; then
  printf '%s\n' 'Refusing to install a Web bundle without the Pilot bearer-login contract. Build with pnpm build:pilot.' >&2
  exit 1
fi

if test -e /opt/hotel-ai-os/current && test ! -L /opt/hotel-ai-os/current; then
  printf '%s\n' '/opt/hotel-ai-os/current is not a symlink.' >&2
  exit 1
fi
if test -e /srv/www/hotel-ai-os/current && test ! -L /srv/www/hotel-ai-os/current; then
  printf '%s\n' '/srv/www/hotel-ai-os/current is not a symlink.' >&2
  exit 1
fi
if test -e "${backend_release}" || test -e "${web_release}"; then
  printf '%s\n' 'Release directory already exists.' >&2
  exit 1
fi

install -d -o root -g root -m 0755 "${backend_release}"
install -d -o root -g root -m 0755 "${web_release}"
install -o root -g root -m 0644 "${stage_root}/core-api.jar" "${backend_release}/core-api.jar"
install -o root -g root -m 0755 \
  "${stage_root}/run-migration-once.sh" \
  "${backend_release}/run-migration-once.sh"
cp -a "${stage_root}/web/." "${web_release}/"
chown -R root:root "${web_release}"
find "${web_release}" -type d -exec chmod 0755 {} +
find "${web_release}" -type f -exec chmod 0644 {} +

ln -sfn "${backend_release}" /opt/hotel-ai-os/current
ln -sfn "${web_release}" /srv/www/hotel-ai-os/current

printf '%s\n' 'RELEASE_INSTALL_COMPLETE'
readlink -f /opt/hotel-ai-os/current
readlink -f /srv/www/hotel-ai-os/current
sha256sum /opt/hotel-ai-os/current/core-api.jar
sha256sum /srv/www/hotel-ai-os/current/index.html
