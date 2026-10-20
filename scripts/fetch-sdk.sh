#!/usr/bin/env bash
# Fetches the WearTAK plugin SDK (.aar) from the TAK.gov plugin template into app/libs.
set -euo pipefail
ref="${1:-master}"
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse \
    https://git.tak.gov/weartak-plugins/weartak-plugin-template.git "$tmp/t"
git -C "$tmp/t" sparse-checkout set app/libs
mkdir -p "$root/app/libs"
cp "$tmp"/t/app/libs/weartak-plugin-sdk-*.aar "$root/app/libs/"
ls "$root/app/libs"/*.aar