#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

account=sfg-backup-pull
group=sfg-backup-pull
export_root=/srv/sfg-backup-export
authorized_keys_dir=/etc/ssh/authorized_keys
authorized_key_file=${authorized_keys_dir}/${account}
fragment=/etc/ssh/sshd_config.d/60-sfg-backup-pull.conf

if [[ ${EUID} -ne 0 ]]; then
  printf '%s\n' 'must run as root' >&2
  exit 1
fi
IFS= read -r supplied_key || true
supplied_key=${supplied_key%$'\r'}
if IFS= read -r extra_input; then
  extra_input=${extra_input%$'\r'}
  if [[ -n ${extra_input} ]]; then
    printf '%s\n' 'unexpected extra public-key input' >&2
    exit 1
  fi
fi
if [[ ! ${supplied_key} =~ ^ssh-ed25519[[:space:]]+[A-Za-z0-9+/]{68}([[:space:]][A-Za-z0-9._@+-]+)?$ ]]; then
  printf '%s\n' 'invalid device public key' >&2
  exit 1
fi
canonical_key="$(awk '{print $1 " " $2}' <<<"${supplied_key}")"
key_probe="$(mktemp)"
fragment_probe="$(mktemp /etc/ssh/sshd_config.d/.60-sfg-backup-pull.XXXXXX)"
previous_fragment="$(mktemp)"
had_fragment=false
cleanup() {
  rm -f -- "${key_probe}" "${fragment_probe}" "${previous_fragment}"
}
trap cleanup EXIT INT TERM
printf '%s\n' "${canonical_key}" >"${key_probe}"
ssh-keygen -l -E sha256 -f "${key_probe}" >/dev/null

if ! getent group "${group}" >/dev/null; then
  groupadd --system "${group}"
fi
if ! getent passwd "${account}" >/dev/null; then
  useradd --system --gid "${group}" --home-dir / --shell /usr/sbin/nologin "${account}"
fi
passwd --lock "${account}" >/dev/null
account_record="$(getent passwd "${account}")"
IFS=: read -r _ _ _ account_gid _ account_home account_shell <<<"${account_record}"
test "${account_gid}" = "$(getent group "${group}" | cut -d: -f3)"
test "${account_home}" = '/'
test "${account_shell}" = '/usr/sbin/nologin'

install -d -m 0755 -o root -g root "${export_root}"
install -d -m 0750 -o root -g "${group}" \
  "${export_root}/data" "${export_root}/receipts"
install -d -m 0755 -o root -g root "${authorized_keys_dir}"
install -m 0640 -o root -g "${group}" "${key_probe}" "${authorized_key_file}"

cat >"${fragment_probe}" <<'EOF'
AllowUsers sfg-backup-pull

Match User sfg-backup-pull
    ChrootDirectory /srv/sfg-backup-export
    ForceCommand internal-sftp -R -l INFO -f AUTHPRIV -d /
    AuthorizedKeysFile /etc/ssh/authorized_keys/%u
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PubkeyAuthentication yes
    AuthenticationMethods publickey
    AllowTcpForwarding no
    AllowAgentForwarding no
    X11Forwarding no
    PermitTunnel no
    PermitTTY no
EOF
chmod 0600 "${fragment_probe}"
chown root:root "${fragment_probe}"
if [[ -f ${fragment} ]]; then
  cp --preserve=mode,ownership,timestamps -- "${fragment}" "${previous_fragment}"
  had_fragment=true
fi
install -m 0600 -o root -g root "${fragment_probe}" "${fragment}"
if ! sshd -t; then
  if [[ ${had_fragment} == true ]]; then
    install -m 0600 -o root -g root "${previous_fragment}" "${fragment}"
  else
    rm -f -- "${fragment}"
  fi
  printf '%s\n' 'sshd configuration rejected; previous configuration restored' >&2
  exit 1
fi
effective="$(sshd -T -C user=${account},host=localhost,addr=127.0.0.1)"
grep -Fxq 'allowusers sfg-backup-pull' <<<"${effective}"
grep -Fxq 'chrootdirectory /srv/sfg-backup-export' <<<"${effective}"
grep -Fxq 'forcecommand internal-sftp -R -l INFO -f AUTHPRIV -d /' <<<"${effective}"
grep -Fxq 'passwordauthentication no' <<<"${effective}"
grep -Fxq 'kbdinteractiveauthentication no' <<<"${effective}"
grep -Fxq 'allowtcpforwarding no' <<<"${effective}"
grep -Fxq 'allowagentforwarding no' <<<"${effective}"
grep -Fxq 'x11forwarding no' <<<"${effective}"
grep -Fxq 'permittty no' <<<"${effective}"
systemctl reload ssh.service
printf '%s\n' 'BACKUP_PULL_ACCOUNT_OK'
