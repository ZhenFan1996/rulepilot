#!/bin/sh
set -eu

if [ "${RULEPILOT_ALLOW_PAID_CANARY:-}" != "true" ]; then
  echo "Refusing paid teaching canary. Set RULEPILOT_ALLOW_PAID_CANARY=true explicitly." >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)

if [ -f "$repo_dir/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$repo_dir/.env"
  set +a
fi

cd "$repo_dir/backend"
./mvnw -q \
  -DRULEPILOT_ALLOW_PAID_CANARY=true \
  -Dtest=TeachingRichLessonPaidCanaryTest#plansAndPublishesACompleteMultiChapterLessonFromOneRealRulebookSlice \
  test
