#!/usr/bin/env bash
# Fetches the WearTAK plugin SDK (.aar) from the TAK.gov plugin template into app/libs.
# Locally this uses your git.tak.gov credentials. In GitLab CI it uses SDK_READ_TOKEN
# (read_repository) if set, otherwise CI_JOB_TOKEN (the template must allow this project).
set -euo pipefail
ref="${1:-master}"
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="git.tak.gov/weartak-plugins/weartak-plugin-template.git"
if [ -n "${SDK_READ_TOKEN:-}" ]; then
    url="https://oauth2:${SDK_READ_TOKEN}@${repo}"
elif [ -n "${CI_JOB_TOKEN:-}" ]; then
    url="https://gitlab-ci-token:${CI_JOB_TOKEN}@${repo}"
else
    url="https://${repo}"
fi
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse "$url" "$tmp/t" 2>/dev/null || {
    echo "ERROR: cannot clone ${repo} (ref ${ref}). Check git.tak.gov access; in CI set SDK_READ_TOKEN" \
         "or allow this project in the template's CI/CD job token settings." >&2
    exit 1
}
git -C "$tmp/t" sparse-checkout set app/libs
mkdir -p "$root/app/libs"
cp "$tmp"/t/app/libs/weartak-plugin-sdk-*.aar "$root/app/libs/"
ls "$root/app/libs"/*.aar