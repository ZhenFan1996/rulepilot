#!/bin/sh

set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$repo_dir/scripts/require-paid-canary-authorization.sh"

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
