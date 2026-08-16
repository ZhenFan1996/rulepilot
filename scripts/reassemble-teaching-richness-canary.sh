#!/bin/sh
set -eu

if [ "${RULEPILOT_ALLOW_CAPTURED_TEACHING_REASSEMBLY:-}" != "true" ]; then
  echo "Refusing captured teaching reassembly. Set RULEPILOT_ALLOW_CAPTURED_TEACHING_REASSEMBLY=true explicitly." >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)

cd "$repo_dir/backend"
./mvnw -q \
  -Dtest=TeachingRichLessonCapturedReassemblyTest#reassemblesTheExactCapturedSupportedSectionsWithoutReinferringEnglishAnchorsFromChineseProse \
  test
