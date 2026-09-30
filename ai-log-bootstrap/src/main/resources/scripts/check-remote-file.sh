#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 4 || "$#" -gt 5 ]]; then
  echo "usage: check-remote-file.sh host port username remote_file [identity_file]" >&2
  exit 64
fi

host="$1"
port="$2"
username="$3"
remote_file="$4"
identity_file="${5:-}"

if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
  echo "invalid SSH port" >&2
  exit 64
fi

ssh_args=(-p "$port" -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=yes)
if [[ -n "$identity_file" ]]; then
  ssh_args+=(-i "$identity_file")
fi

# The -f predicate consumes its path argument; test does not accept -- here.
printf -v quoted_file '%q' "$remote_file"
# 0=普通文件，1=不存在或非普通文件，10=软链接；SSH 错误码保持原样。
remote_command="if test -L ${quoted_file}; then exit 10; fi; test -f ${quoted_file}"
exec ssh "${ssh_args[@]}" "${username}@${host}" "$remote_command"
