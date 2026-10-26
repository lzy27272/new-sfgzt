#!/bin/bash
# Runner-only transport. The fixed root FULL entry still validates the complete immutable CI ZIP.
set -Eeuo pipefail
umask 077
uploads=() work='' remote_created=false delivered=false

stop_uploads(){
  local pid
  # Monitor mode gives each scp its own group, including its SSH child. Stop and reap all before remote cleanup.
  for pid in "${uploads[@]}"; do kill -TERM -- "-$pid" 2>/dev/null || true; done
  if test "${#uploads[@]}" -gt 0; then
    sleep 0.2
    for pid in "${uploads[@]}"; do kill -KILL -- "-$pid" 2>/dev/null || true; done
    for pid in "${uploads[@]}"; do wait "$pid" 2>/dev/null || true; done
  fi
  uploads=()
}
cleanup_transfer(){
  local result=$?
  trap - EXIT INT TERM
  stop_uploads
  if test "$remote_created" = true && test "$delivered" != true; then
    ssh "${options[@]}" "$target" "if test -d '$remote_stage' && test ! -L '$remote_stage'; then rm -f -- '$remote_stage/part-00' '$remote_stage/part-01' '$remote_stage/part-02' '$remote_stage/part-03' '$remote_stage/release-artifact.zip.tmp' '$remote_stage/release-artifact.zip'; rmdir -- '$remote_stage'; fi" || printf '%s\n' 'FULL_ARTIFACT_PARTIAL_CLEANUP_REQUIRES_REVIEW' >&2
  fi
  if test -n "$work"; then
    rm -f -- "$work/part-00" "$work/part-01" "$work/part-02" "$work/part-03"
    rmdir -- "$work"
  fi
  exit "$result"
}
main(){
  test "$#" -eq 6 || { echo 'Usage: send-full-artifact archive remote-stage target sha256 key known-hosts' >&2; return 2; }
  local archive=$1 digest=$4 key=$5 known_hosts=$6 bytes index pid
  remote_stage=$2; target=$3
  [[ "$remote_stage" =~ ^/tmp/hotel-ai-os-full-ci-incoming/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$remote_stage" != *'..'* ]]
  [[ "$target" =~ ^hotel-ai-deploy@[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ && "$digest" =~ ^[a-f0-9]{64}$ ]]
  test -f "$archive" && test ! -L "$archive" && test -f "$key" && test -f "$known_hosts"
  bytes="$(stat -c '%s' -- "$archive")"; test "$bytes" -ge 4 && test "$bytes" -le 3221225472
  test "$(sha256sum "$archive" | cut -d' ' -f1)" = "$digest"
  options=(-i "$key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known_hosts" -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -o ControlMaster=no -o ControlPath=none)
  work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/full-artifact.XXXXXXXX")"
  trap cleanup_transfer EXIT; trap 'exit 130' INT; trap 'exit 143' TERM
  split -n 4 --numeric-suffixes=0 --suffix-length=2 -- "$archive" "$work/part-"
  for index in 00 01 02 03; do test -f "$work/part-$index"; done
  ssh "${options[@]}" "$target" "test ! -e '$remote_stage' && test ! -L '$remote_stage' && install -d -m 0700 '$remote_stage'"
  remote_created=true
  set -m
  for index in 00 01 02 03; do
    scp "${options[@]}" "$work/part-$index" "$target:$remote_stage/part-$index" &
    uploads+=("$!")
  done
  for index in "${!uploads[@]}"; do
    pid=${uploads[$index]}
    if wait "$pid"; then unset 'uploads[index]'; else return 1; fi
  done
  ssh "${options[@]}" "$target" "set -eu; umask 077; test -d '$remote_stage' && test ! -L '$remote_stage'; test ! -e '$remote_stage/release-artifact.zip' && test ! -L '$remote_stage/release-artifact.zip'; cat -- '$remote_stage/part-00' '$remote_stage/part-01' '$remote_stage/part-02' '$remote_stage/part-03' > '$remote_stage/release-artifact.zip.tmp'; printf '%s  %s\n' '$digest' '$remote_stage/release-artifact.zip.tmp' | sha256sum --check --status; chmod 0600 '$remote_stage/release-artifact.zip.tmp'; mv -T -- '$remote_stage/release-artifact.zip.tmp' '$remote_stage/release-artifact.zip'; rm -f -- '$remote_stage/part-00' '$remote_stage/part-01' '$remote_stage/part-02' '$remote_stage/part-03'"
  delivered=true
  printf 'FULL_ARTIFACT_TRANSFER_PASS parts=4 bytes=%s sha256=%s\n' "$bytes" "$digest"
}
if [[ "${BASH_SOURCE[0]}" = "$0" ]]; then main "$@"; fi
