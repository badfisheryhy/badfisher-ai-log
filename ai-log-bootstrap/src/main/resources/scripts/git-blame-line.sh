#!/usr/bin/env bash
set -u

repository_directory=${1:-}
java_class_name=${2:-}
source_line_number=${3:-}
timeout_seconds=${4:-15}

fail() {
    echo "$1" >&2
    exit 1
}

[ -n "$repository_directory" ] || fail "repository directory is required"
[ -n "$java_class_name" ] || fail "java class name is required"
case "$source_line_number" in
    ''|*[!0-9]*) fail "source line number must be a positive integer" ;;
esac
[ "$source_line_number" -gt 0 ] || fail "source line number must be positive"
case "$timeout_seconds" in
    ''|*[!0-9]*) fail "timeout seconds must be a positive integer" ;;
esac
[ "$timeout_seconds" -gt 0 ] || fail "timeout seconds must be positive"
[ "$timeout_seconds" -le 60 ] || fail "timeout seconds must not exceed 60"

if [ "${GIT_BLAME_TIMEOUT_GUARD:-}" != "1" ]; then
    export GIT_BLAME_TIMEOUT_GUARD=1
    exec timeout --signal=TERM --kill-after=5s "$timeout_seconds" \
        "$BASH" "$0" "$repository_directory" "$java_class_name" \
        "$source_line_number" "$timeout_seconds" </dev/null
fi

case "$java_class_name" in
    *[!A-Za-z0-9_.$]*) fail "java class name contains unsafe characters" ;;
esac

cd "$repository_directory" || fail "repository directory is unavailable"
[ -d .git ] || fail "repository metadata is unavailable"
[ ! -f .git/shallow ] || fail "shallow repository cannot provide reliable blame ownership"

snapshot_commit=$(git rev-parse --verify HEAD 2>/dev/null) \
    || fail "unable to resolve repository HEAD"
case "$snapshot_commit" in
    *[!0-9a-fA-F]*|'') fail "repository HEAD is invalid" ;;
esac

outer_class=${java_class_name%%\$*}
relative_file=${outer_class//./\/}.java
candidate=
candidate_count=0
tree_entries=$(mktemp) || fail "unable to create temporary file"
trap 'rm -f "$tree_entries"' EXIT
git ls-tree -rz --full-tree "$snapshot_commit" >"$tree_entries" \
    || fail "unable to list repository tree"

while IFS= read -r -d '' tree_entry; do
    metadata=${tree_entry%%$'\t'*}
    source_file=${tree_entry#*$'\t'}
    mode=${metadata%% *}
    object_type=${metadata#* }
    object_type=${object_type%% *}
    case "$source_file" in
        "src/main/java/$relative_file"|*/"src/main/java/$relative_file")
            if [ "$object_type" = "blob" ] && [ "$mode" != "120000" ]; then
                candidate=$source_file
                candidate_count=$((candidate_count + 1))
            fi
            ;;
    esac
done <"$tree_entries"

[ "$candidate_count" -eq 1 ] || fail "source file match is missing or ambiguous"

blame_output=$(GIT_TERMINAL_PROMPT=0 git blame -p \
    -L "$source_line_number,$source_line_number" "$snapshot_commit" -- "$candidate" \
    </dev/null) || fail "git blame command failed"

author=$(printf '%s\n' "$blame_output" | sed -n 's/^author //p' | head -n 1)
author_time=$(printf '%s\n' "$blame_output" | sed -n 's/^author-time //p' | head -n 1)
[ -n "$author" ] || fail "git blame author is empty"
case "$author_time" in
    ''|*[!0-9-]*) fail "git blame author-time is invalid" ;;
esac

printf 'AUTHOR\t%s\n' "$author"
printf 'AUTHOR_TIME\t%s\n' "$author_time"
