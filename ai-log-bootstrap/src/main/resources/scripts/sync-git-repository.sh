#!/usr/bin/env bash
set -euo pipefail

readonly EXIT_USAGE=64
readonly EXIT_REPOSITORY=65
readonly EXIT_DEPENDENCY=69
readonly EXIT_LOCAL_STATE=73
readonly EXIT_IO=74
readonly EXIT_LOCKED=75
readonly EXIT_GIT=76

fail() {
  local exit_code="$1"
  local message="$2"
  printf 'GIT_SYNC_ERROR|code=%s|message=%s\n' "$exit_code" "$message" >&2
  exit "$exit_code"
}

# Git Bash's native launcher may convert /c/... arguments back to C:/... .
is_absolute_path() {
  [[ "$1" == /* ]] || {
    [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]] \
      && [[ "$1" =~ ^[A-Za-z]:[/\\] ]]
  }
}

if [[ "$#" -lt 4 || "$#" -gt 7 ]]; then
  echo "usage: sync-git-repository.sh repository_url branch workspace_root local_sub_directory [credential_file] [timeout_seconds] [allow_insecure_http]" >&2
  exit "$EXIT_USAGE"
fi

for dependency in git realpath mktemp sed timeout; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    fail "$EXIT_DEPENDENCY" "required command is unavailable: ${dependency}"
  fi
done

timeout_seconds="${6:-600}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] || (( timeout_seconds > 3600 )); then
  fail "$EXIT_USAGE" "timeout must be between 1 and 3600 seconds"
fi
# Guard the complete operation, including Git descendants, before taking locks.
if [[ "${GIT_SYNC_TIMEOUT_GUARD:-}" != "1" ]]; then
  export GIT_SYNC_TIMEOUT_GUARD=1
  exec timeout --signal=TERM --kill-after=5s "${timeout_seconds}s" "$BASH" "$0" "$@"
fi

allow_insecure_http="${7:-false}"
if [[ "$allow_insecure_http" != "true" && "$allow_insecure_http" != "false" ]]; then
  fail "$EXIT_USAGE" "allow_insecure_http must be true or false"
fi
repository_url="$1"
branch="$2"
workspace_root_input="$3"
local_sub_directory="$4"
credential_file="${5:-}"
if [[ "$credential_file" == "-" ]]; then
  credential_file=""
fi

if [[ -z "$repository_url" || "$repository_url" == -* \
    || "$repository_url" == *[[:space:]]* ]]; then
  fail "$EXIT_USAGE" "invalid repository URL"
fi

if [[ "$repository_url" != http://* && "$repository_url" != https://* ]]; then
  fail "$EXIT_USAGE" "only HTTP(S) repository URLs are supported"
fi
if [[ "$repository_url" == http://* && "$allow_insecure_http" != "true" ]]; then
  fail "$EXIT_USAGE" "HTTP requires explicit allow_insecure_http=true"
fi
if [[ "$repository_url" =~ ^https?://[^/]*@ ]]; then
  fail "$EXIT_USAGE" "credentials must not be embedded in repository URL"
fi

if [[ ! "$branch" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$ ]] \
    || ! git check-ref-format --branch "$branch" >/dev/null 2>&1; then
  fail "$EXIT_USAGE" "invalid Git branch"
fi

if ! is_absolute_path "$workspace_root_input"; then
  fail "$EXIT_USAGE" "workspace root must be an absolute path"
fi
if [[ ! "$local_sub_directory" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  fail "$EXIT_USAGE" "local sub-directory must be a safe single-level name"
fi

if ! mkdir -p -- "$workspace_root_input"; then
  fail "$EXIT_IO" "unable to create workspace root"
fi
if ! workspace_root="$(realpath -e -- "$workspace_root_input")"; then
  fail "$EXIT_IO" "unable to resolve workspace root"
fi
if [[ "$workspace_root" == "/" ]]; then
  fail "$EXIT_USAGE" "filesystem root cannot be used as workspace root"
fi

if [[ -L "${workspace_root}/${local_sub_directory}" ]]; then
  fail "$EXIT_LOCAL_STATE" "target directory must not be a symbolic link"
fi
target_directory="$(realpath -m -- "${workspace_root}/${local_sub_directory}")"
if [[ "$target_directory" != "${workspace_root}/"* ]]; then
  fail "$EXIT_USAGE" "target directory escapes workspace root"
fi

lock_file="${workspace_root}/.${local_sub_directory}.git-sync.lock"
lock_directory=""
temporary_clone=""
askpass_script=""
cleanup() {
  if [[ -n "$askpass_script" && -f "$askpass_script" ]]; then
    rm -f -- "$askpass_script"
  fi
  if [[ -n "$temporary_clone" && -d "$temporary_clone" \
      && "$temporary_clone" == "${workspace_root}/.${local_sub_directory}.clone."* ]]; then
    rm -rf -- "$temporary_clone"
  fi
  if [[ -n "$lock_directory" ]]; then
    rmdir -- "$lock_directory" || true
  fi
}
trap cleanup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

if command -v flock >/dev/null 2>&1; then
  exec 9>"$lock_file"
  if ! flock -w 5 9; then
    fail "$EXIT_LOCKED" "repository synchronization is already running"
  fi
else
  # Git Bash lacks flock. Never remove another process's directory lock.
  if ! mkdir -- "${lock_file}.d" 2>/dev/null; then
    fail "$EXIT_LOCKED" "repository directory lock exists; verify its owner before recovery"
  fi
  lock_directory="${lock_file}.d"
fi

if [[ -n "$credential_file" ]]; then
  if ! is_absolute_path "$credential_file" || [[ ! -f "$credential_file" ]]; then
    fail "$EXIT_USAGE" "credential file must be an existing absolute file"
  fi
  if ! credential_file="$(realpath -e -- "$credential_file")"; then
    fail "$EXIT_USAGE" "unable to resolve credential file"
  fi
fi

# 旧 Git 可能忽略 GIT_TERMINAL_PROMPT；下方同时关闭标准输入，
# 避免 Job 的输入管道一直打开导致等待。整个过程仍由 timeout 限时。
git_environment=(env -u GIT_SSL_NO_VERIFY GIT_TERMINAL_PROMPT=0)
if [[ -n "$credential_file" ]]; then
  http_username="$(sed -n '1{s/\r$//;p;}' "$credential_file")"
  http_token="$(sed -n '2{s/\r$//;p;}' "$credential_file")"
  if [[ -z "$http_username" || -z "$http_token" ]]; then
    fail "$EXIT_USAGE" "HTTP credential file must contain username and token on separate lines"
  fi
  unset http_username http_token

  askpass_script="$(mktemp "${workspace_root}/.git-askpass.XXXXXX")"
  cat >"$askpass_script" <<'ASKPASS'
#!/bin/sh
set -eu

prompt="${1:-}"
case "$prompt" in
  *Username*|*username*)
    sed -n '1{s/\r$//;p;}' "$GIT_HTTP_CREDENTIAL_FILE"
    ;;
  *Password*|*password*)
    sed -n '2{s/\r$//;p;}' "$GIT_HTTP_CREDENTIAL_FILE"
    ;;
  *)
    exit 1
    ;;
esac
ASKPASS
  chmod 700 "$askpass_script"
  git_environment+=(
    GIT_ASKPASS="$askpass_script"
    GIT_HTTP_CREDENTIAL_FILE="$credential_file"
  )
fi

git_exec() {
  local http_protocol="never"
  if [[ "$allow_insecure_http" == "true" ]]; then
    http_protocol="always"
  fi
  "${git_environment[@]}" git -c http.followRedirects=false -c http.sslVerify=true \
    -c protocol.allow=never -c protocol.https.allow=always \
    -c "protocol.http.allow=${http_protocol}" "$@" </dev/null
}

# 兼容 Git 1.8.3.1：它不支持 git -C。仅在子 Shell 内切换目录，
# 不改变主脚本的工作目录；切换失败立即返回，禁止在错误目录执行 Git。
# 保留 checkout --detach，明确按 commit 检出，不切换或移动本地分支。
git_exec_in() {
  local repository_directory="$1"
  shift
  (
    cd -- "$repository_directory" || exit 1
    git_exec "$@"
  )
}

resolve_commit() {
  local repository_directory="$1"
  local commit
  if ! commit="$(git_exec_in "$repository_directory" rev-parse \
      "refs/remotes/origin/${branch}^{commit}")"; then
    fail "$EXIT_REPOSITORY" "configured branch was not found after fetch"
  fi
  if [[ ! "$commit" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
    fail "$EXIT_REPOSITORY" "repository returned an invalid commit SHA"
  fi
  printf '%s' "$commit"
}

action=""
commit_sha=""
if [[ ! -e "$target_directory" ]]; then
  temporary_clone="$(mktemp -d \
      "${workspace_root}/.${local_sub_directory}.clone.XXXXXX")"
  if ! git_exec clone --no-checkout --single-branch --branch "$branch" -- \
      "$repository_url" "$temporary_clone" >&2; then
    fail "$EXIT_GIT" "Git clone failed"
  fi
  commit_sha="$(resolve_commit "$temporary_clone")"
  if ! git_exec_in "$temporary_clone" checkout --detach "$commit_sha" >&2; then
    fail "$EXIT_GIT" "Git checkout failed after clone"
  fi
  if ! mv -- "$temporary_clone" "$target_directory"; then
    fail "$EXIT_IO" "unable to activate cloned repository"
  fi
  temporary_clone=""
  action="CLONED"
else
  if [[ ! -d "$target_directory" ]] \
      || ! git_exec_in "$target_directory" rev-parse --is-inside-work-tree \
          >/dev/null 2>&1; then
    fail "$EXIT_LOCAL_STATE" "target exists but is not a Git working tree"
  fi

  # Compare the stored URL, not remote get-url's insteadOf-expanded transport URL.
  if ! configured_remote="$(git_exec_in "$target_directory" config --get remote.origin.url)"; then
    fail "$EXIT_LOCAL_STATE" "Git working tree has no origin remote"
  fi
  if [[ "$configured_remote" != "$repository_url" ]]; then
    fail "$EXIT_LOCAL_STATE" "origin remote does not match configured repository"
  fi

  if ! working_tree_status="$(git_exec_in "$target_directory" status \
      --porcelain --untracked-files=all)"; then
    fail "$EXIT_LOCAL_STATE" "unable to inspect Git working tree"
  fi
  if [[ -n "$working_tree_status" ]]; then
    fail "$EXIT_LOCAL_STATE" "Git working tree contains local changes"
  fi

  if ! git_exec_in "$target_directory" fetch --prune --no-tags origin \
      "+refs/heads/${branch}:refs/remotes/origin/${branch}" >&2; then
    fail "$EXIT_GIT" "Git fetch failed"
  fi
  commit_sha="$(resolve_commit "$target_directory")"
  if ! current_commit="$(git_exec_in "$target_directory" rev-parse HEAD)"; then
    fail "$EXIT_LOCAL_STATE" "unable to resolve current commit"
  fi
  if [[ "$current_commit" == "$commit_sha" ]]; then
    action="REUSED"
  else
    if ! git_exec_in "$target_directory" checkout --detach "$commit_sha" >&2; then
      fail "$EXIT_GIT" "Git checkout failed after fetch"
    fi
    action="UPDATED"
  fi
fi

printf '{"status":"READY","action":"%s","commitSha":"%s","branch":"%s","localSubDirectory":"%s"}\n' \
  "$action" "$commit_sha" "$branch" "$local_sub_directory"
