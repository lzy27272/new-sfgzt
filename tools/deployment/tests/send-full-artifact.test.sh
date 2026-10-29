#!/bin/bash
set -Eeuo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
script="$here/../send-full-artifact.sh"
fixture="$(mktemp -d "${TMPDIR:-/tmp}/full-transfer-test.XXXXXXXX")"
cleanup_fixture(){ local result=$?; if test "$result" -ne 0; then cat "$fixture/stderr" 2>/dev/null || true; fi; find "$fixture" -xdev -depth -delete; }
trap cleanup_fixture EXIT
export fixture
mkdir "$fixture/remote" "$fixture/runner"
export RUNNER_TEMP="$fixture/runner"
printf 'synthetic SSH key\n' >"$fixture/key"; printf 'synthetic pinned host\n' >"$fixture/hosts"
head -c 131075 /dev/urandom >"$fixture/artifact.zip"
digest="$(sha256sum "$fixture/artifact.zip" | cut -d' ' -f1)"
export transfer_mode=success
ssh(){
  local command="${!#}"
  command="${command//\/tmp\/hotel-ai-os-full-ci-incoming/$fixture/remote}"
  # Windows Git Bash cannot apply Linux install ownership/modes; the transport fixture uses its private mkdir.
  command="${command//install -d -m 0700/mkdir}"
  if [[ "$command" == *'rmdir --'* ]]; then printf 'CLEANUP\n' >>"$fixture/events"; fi
  bash -c "$command"
}
scp(){
  set +m
  local args=("$@") source destination name
  source="${args[${#args[@]}-2]}"; destination="${args[${#args[@]}-1]#*:}"
  destination="${destination//\/tmp\/hotel-ai-os-full-ci-incoming/$fixture/remote}"
  name="${source##*/}"
  printf 'START %s %s\n' "$BASHPID" "$name" >>"$fixture/events"
  if test "$transfer_mode" = failure && test "$name" = part-00; then
    sleep 10 &
    printf 'CHILD %s %s\n' "$!" "$name" >>"$fixture/events"
    sleep 0.1; return 7
  fi
  if test "$transfer_mode" = signal || test "$transfer_mode" = failure; then sleep 10; else sleep 0.3; fi
  cp -- "$source" "$destination"
  if test "$transfer_mode" = corrupt && test "$name" = part-02; then printf x >>"$destination"; fi
  printf 'FINISH %s %s\n' "$BASHPID" "$name" >>"$fixture/events"
}
export -f ssh scp
run_transfer(){ bash "$script" "$fixture/artifact.zip" "/tmp/hotel-ai-os-full-ci-incoming/$1" hotel-ai-deploy@fixture.example "$digest" "$fixture/key" "$fixture/hosts"; }
run_transfer success >"$fixture/result" 2>"$fixture/stderr"
cmp --silent "$fixture/artifact.zip" "$fixture/remote/success/release-artifact.zip"
test "$(find "$fixture/remote/success" -type f | wc -l)" -eq 1
test "$(grep -c '^START ' "$fixture/events")" -eq 4
test "$(head -n 4 "$fixture/events" | grep -c '^START ')" -eq 4
grep -Fq 'FULL_ARTIFACT_TRANSFER_PASS parts=4' "$fixture/result"
printf 'PASS four concurrent parts reconstruct the exact approved bytes and leave one artifact\n'

: >"$fixture/events"; export transfer_mode=failure
if run_transfer failure >"$fixture/result" 2>"$fixture/stderr"; then exit 1; fi
test ! -e "$fixture/remote/failure"
test "$(tail -n 1 "$fixture/events")" = CLEANUP
while read -r _ pid _; do ! kill -0 "$pid" 2>/dev/null; done < <(grep '^START ' "$fixture/events")
while read -r _ pid _; do ! kill -0 "$pid" 2>/dev/null; done < <(grep '^CHILD ' "$fixture/events")
printf 'PASS failed scp terminates and reaps every upload before removing fixed partial paths\n'

: >"$fixture/events"; export transfer_mode=corrupt
if run_transfer corrupt >"$fixture/result" 2>"$fixture/stderr"; then exit 1; fi
test ! -e "$fixture/remote/corrupt"
printf 'PASS altered remote part fails whole-artifact SHA and cleans all fixed paths\n'

: >"$fixture/events"; export transfer_mode=signal
bash "$script" "$fixture/artifact.zip" /tmp/hotel-ai-os-full-ci-incoming/signal hotel-ai-deploy@fixture.example "$digest" "$fixture/key" "$fixture/hosts" >"$fixture/result" 2>"$fixture/stderr" &
parent=$!
for _ in {1..100}; do test "$(grep -c '^START ' "$fixture/events" || true)" -eq 4 && break; sleep 0.02; done
kill -TERM "$parent"
if wait "$parent"; then exit 1; fi
test ! -e "$fixture/remote/signal"
test "$(tail -n 1 "$fixture/events")" = CLEANUP
while read -r _ pid _; do ! kill -0 "$pid" 2>/dev/null; done < <(grep '^START ' "$fixture/events")
printf 'PASS cancellation joins all upload process groups before remote cleanup\n'

export transfer_mode=success
if bash "$script" "$fixture/artifact.zip" /tmp/hotel-ai-os-full-ci-incoming/../escape hotel-ai-deploy@fixture.example "$digest" "$fixture/key" "$fixture/hosts" >/dev/null 2>&1; then exit 1; fi
test ! -e "$fixture/remote/escape"
printf 'PASS unsafe incoming boundary rejected before transport\n'
mkdir "$fixture/remote/existing"; printf 'keep' >"$fixture/remote/existing/sentinel"
if run_transfer existing >"$fixture/result" 2>"$fixture/stderr"; then exit 1; fi
test "$(cat "$fixture/remote/existing/sentinel")" = keep
test "$(find "$fixture/remote/existing" -type f | wc -l)" -eq 1
printf 'PASS a preexisting incoming directory is rejected without deleting its sentinel\n'

# Exercise the actual workflow functions, not a second implementation of the budget calculation.
workflow="$here/../../../.github/workflows/pilot-deploy.yml"
full_job="$(awk '/^  deploy-full-release:/{inside=1;next} inside&&/^  [A-Za-z0-9_-]+:/{exit} inside{sub(/\r$/,"");print}' "$workflow")"
test "$(printf '%s\n' "$full_job" | sed -n 's/^    timeout-minutes: \([0-9][0-9]*\)$/\1/p')" = 120
workflow_function(){
  awk -v name="$1" '{sub(/\r$/,"")} $0=="          "name"(){"{inside=1} inside{sub(/^          /,"");print;if($0=="}"){found=1;exit}} END{if(!found)exit 1}' "$workflow"
}
eval "$(workflow_function initialize_full_release_window)"
eval "$(workflow_function require_full_release_window)"
start_line="$(printf '%s\n' "$full_job" | grep -nFx '          initialize_full_release_window' | cut -d: -f1)"
checkout_line="$(printf '%s\n' "$full_job" | grep -nF '      - name: Check out' | head -n 1 | cut -d: -f1)"
guard_line="$(printf '%s\n' "$full_job" | grep -nFx '          require_full_release_window' | cut -d: -f1)"
deploy_line="$(printf '%s\n' "$full_job" | grep -nF "&& sudo '\$full' DEPLOY" | cut -d: -f1)"
test "$start_line" -lt "$checkout_line"
test "$((guard_line + 1))" -eq "$deploy_line"
export GITHUB_ENV="$fixture/window.env"
window_clock=1700000000
date(){ test "$#" -eq 1 && test "$1" = +%s || return 91; printf '%s\n' "$window_clock"; }
initialize_full_release_window
test "$(cat "$GITHUB_ENV")" = FULL_RELEASE_DEADLINE_EPOCH=1700006900
FULL_RELEASE_DEADLINE_EPOCH="$(sed -n 's/^FULL_RELEASE_DEADLINE_EPOCH=//p' "$GITHUB_ENV")"
require_full_release_window >"$fixture/window-result"
grep -Fq 'FULL_RELEASE_WINDOW_PASS remainingSeconds=6900 requiredSeconds=2400' "$fixture/window-result"
window_clock=1700004500
require_full_release_window >"$fixture/window-result"
grep -Fq 'FULL_RELEASE_WINDOW_PASS remainingSeconds=2400 requiredSeconds=2400' "$fixture/window-result"
printf 'PASS FULL window starts before checkout, includes setup margin, and accepts the exact 40-minute boundary\n'

window_clock=1700004501
if require_full_release_window >"$fixture/window-result" 2>&1; then exit 1; fi
grep -Fq 'FULL_RELEASE_WINDOW_INSUFFICIENT remainingSeconds=2399 requiredSeconds=2400' "$fixture/window-result"
test ! -e "$fixture/root-deploy-called"
if require_full_release_window >"$fixture/window-result" 2>&1; then printf 'DEPLOY\n' >"$fixture/root-deploy-called"; fi
test ! -e "$fixture/root-deploy-called"
window_clock=1700007000
if require_full_release_window >"$fixture/window-result" 2>&1; then exit 1; fi
grep -Fq 'FULL_RELEASE_WINDOW_INSUFFICIENT' "$fixture/window-result"
printf 'PASS exhausted release budget fails before any fixed root DEPLOY/BEGIN_FULL invocation\n'

window_clock=1700000000
for invalid in '' malformed 17000000000 1700006901; do
  FULL_RELEASE_DEADLINE_EPOCH=$invalid
  if require_full_release_window >"$fixture/window-result" 2>&1; then exit 1; fi
  grep -Fq 'FULL_RELEASE_WINDOW_INVALID' "$fixture/window-result"
done
FULL_RELEASE_DEADLINE_EPOCH=1700006900
window_clock=invalid
if require_full_release_window >"$fixture/window-result" 2>&1; then exit 1; fi
grep -Fq 'FULL_RELEASE_WINDOW_INVALID' "$fixture/window-result"
export GITHUB_ENV="$fixture/invalid-window.env"
if initialize_full_release_window >"$fixture/window-result" 2>&1; then exit 1; fi
test ! -e "$GITHUB_ENV"
printf 'PASS missing, malformed, impossible deadlines and invalid clocks fail closed without persisting a false window\n'
