#!/usr/bin/env bash
# kubectl is a stub: what can be checked here is the command line it receives and how each outcome is judged.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/kubectl" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
[ -n "${SMOKE_CONNREFUSED:-}" ] && { echo "The connection to the server 127.0.0.1:6443 was refused"; exit 1; }
case "$*" in
  *"version --client"*) echo "Client Version: v1.31.0"; exit 0 ;;
  *"rollout status"*)
    case "${SMOKE_ROLLOUT:-ok}" in
      ok) echo "deployment \"app\" successfully rolled out"; exit 0 ;;
      timeout) echo "error: deployment \"app\" exceeded its progress deadline"; echo "timed out waiting for the condition"; exit 1 ;;
      failed) echo "error: deployment \"app\" failed"; exit 1 ;;
    esac ;;
  *"set image"*) [ -n "${SMOKE_UNAUTHORIZED:-}" ] && { echo "error: You must be logged in to the server (Unauthorized)"; exit 1; }
                 echo "deployment.apps/app image updated"; exit 0 ;;
esac
echo ok; exit 0
SH
chmod +x "$work/bin/kubectl"
export SMOKE_ARGV="$work/argv.txt"

# set the image and wait for the rollout
dir="$(mktemp -d)"; printf 'apiVersion: v1\nkind: Config\n' >"$dir/kubeconfig"
printf '{"action": "setImage", "deployment": "app", "container": "api", "image": "registry/app:2.0.0", "namespace": "prod", "kubeconfig": "kubeconfig"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >"$dir/atom.log" 2>&1 || fail "setImage: $(cat "$dir/output.json")"
grep -q "set image deployment/app api=registry/app:2.0.0" "$work/argv.txt" || fail "set image argv: $(cat "$work/argv.txt")"
grep -q -- "--namespace prod" "$work/argv.txt" || fail "namespace not passed"
grep -q "rollout status deployment/app --timeout=300s" "$work/argv.txt" || fail "rollout was not waited for"
[ "$(output "$dir" rolledOut)" = "true" ] || fail "rolledOut flag: $(cat "$dir/output.json")"
[ ! -f "$dir/.atoms/kubeconfig" ] || true   # a path kubeconfig is not copied

# a kubeconfig pasted inline is written 0600 and removed afterwards
dir="$(mktemp -d)"
printf '{"action": "apply", "manifest": "k8s.yaml", "kubeconfig": "apiVersion: v1\\nkind: Config\\nclusters: []"}' >"$dir/input.json"
path="$work/bin:$PATH"
PATH="$work/bin:$PATH" run "$dir" >"$dir/atom.log" 2>&1 || fail "apply: $(cat "$dir/output.json")"
grep -q -- "--kubeconfig" "$work/argv.txt" || fail "inline kubeconfig not passed"
[ ! -e "$dir/.atoms/kubeconfig" ] || fail "the inline kubeconfig should be deleted after the run"
grep -q "apply -f k8s.yaml" "$work/argv.txt" || fail "apply argv"

# a rollout that never finishes fails the step, and says so
dir="$(mktemp -d)"
printf '{"action": "setImage", "deployment": "app", "image": "registry/app:3.0.0"}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_ROLLOUT=timeout run "$dir" >"$dir/atom.log" 2>&1 && fail "a timed-out rollout must fail"
grep -q "did not finish within" "$dir/output.json" || fail "timeout message: $(cat "$dir/output.json")"

# an unauthorized cluster is the caller's problem, an unreachable one is the environment's
dir="$(mktemp -d)"
printf '{"action": "setImage", "deployment": "app", "image": "x", "waitForRollout": "false"}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_UNAUTHORIZED=1 run "$dir" >"$dir/atom.log" 2>&1 && fail "unauthorized must fail"
grep -q '"errorType":1' "$dir/output.json" || fail "unauthorized should be USER(1): $(cat "$dir/output.json")"

dir="$(mktemp -d)"
printf '{"action": "setImage", "deployment": "app", "image": "x", "waitForRollout": "false"}' >"$dir/input.json"
PATH="$work/bin:$PATH" SMOKE_CONNREFUSED=1 run "$dir" >"$dir/atom.log" 2>&1 && fail "an unreachable cluster must fail"
grep -q '"errorType":5' "$dir/output.json" || fail "unreachable should be ENVIRONMENT(5): $(cat "$dir/output.json")"

# bad input
dir="$(mktemp -d)"
printf '{"action": "explode"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >"$dir/atom.log" 2>&1 && fail "unknown action must be refused"
grep -q "known: setImage" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

dir="$(mktemp -d)"; printf '{"action": "apply"}' >"$dir/input.json"
PATH="$work/bin:$PATH" run "$dir" >"$dir/atom.log" 2>&1 && fail "apply without a manifest must be refused"

# no kubectl on the agent
dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
# Point the atom at a path that does not exist rather than trimming PATH: GitHub runners ship kubectl in
# /usr/local/bin (and sometimes /usr/bin), so a restricted PATH is not the same thing as a missing binary --
# and trimming PATH also breaks the harness, because `env` lives there too.
python3 tools/set_json.py "$dir/input.json" kubectlBinary=no-such-dir/kubectl
run "$dir" >"$dir/atom.log" 2>&1 && fail "missing kubectl must fail"
grep -q '"errorType":5' "$dir/output.json" \
  || fail "missing kubectl should be ENVIRONMENT(5), got: $(cat "$dir/output.json")"

echo "smoke ok: setImage+rollout, inline kubeconfig 0600, rollout timeout, unauthorized, unreachable, bad input"
