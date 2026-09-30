#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 5 || "$#" -gt 6 ]]; then
  echo "usage: rsync-file.sh host port username remote_file target_directory [identity_file]" >&2
  exit 64
fi

host="$1"
port="$2"
username="$3"
remote_file="$4"
target_directory="$5"
identity_file="${6:-}"

if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
  echo "invalid SSH port" >&2
  exit 64
fi

mkdir -p -- "$target_directory"

ssh_command="ssh -p ${port} -o BatchMode=yes -o ConnectTimeout=30 -o StrictHostKeyChecking=yes"
if [[ -n "$identity_file" ]]; then
  printf -v quoted_identity '%q' "$identity_file"
  ssh_command+=" -i ${quoted_identity}"
fi

exec rsync -a --partial --partial-dir=.rsync-partial --protect-args --timeout=300 \
  -e "$ssh_command" "${username}@${host}:${remote_file}" "${target_directory}/"
