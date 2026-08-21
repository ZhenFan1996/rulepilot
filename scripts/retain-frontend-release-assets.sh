#!/usr/bin/env bash
set -Eeuo pipefail

if (( $# != 2 )); then
  echo "Usage: $0 <destination-assets-directory> <previous-assets-directory>" >&2
  exit 2
fi

destination_dir=$1
previous_dir=$2
test -d "$destination_dir"
test -d "$previous_dir"

retained_assets=0
while IFS= read -r -d '' previous_asset; do
  asset_name=${previous_asset##*/}
  if [[ ! "$asset_name" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "Refusing unsafe previous frontend asset name: ${asset_name}" >&2
    exit 1
  fi
  destination_asset="${destination_dir}/${asset_name}"
  if [[ ! -e "$destination_asset" ]]; then
    install -m 0644 "$previous_asset" "$destination_asset"
    retained_assets=$((retained_assets + 1))
  fi
done < <(find "$previous_dir" -maxdepth 1 -type f -print0)

printf 'Retained %d previous frontend asset(s) from %s.\n' "$retained_assets" "$previous_dir"
