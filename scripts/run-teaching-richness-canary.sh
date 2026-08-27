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

canary_pdf="$repo_dir/.local/public-corpus/pdfs/the-captain-is-dead.pdf"
if [ ! -f "$canary_pdf" ]; then
  echo "FAIL representative teaching canary rulebook is required: $canary_pdf" >&2
  exit 2
fi

provider=$(printf '%s' "${RULEPILOT_TEACHING_CANARY_PROVIDER:-deepseek}" | tr '[:upper:]' '[:lower:]')
case "$provider" in
  deepseek)
    provider_key=${DEEPSEEK_API_KEY:-}
    provider_base_url=${DEEPSEEK_BASE_URL:-}
    provider_model=${DEEPSEEK_MODEL:-}
    ;;
  qwen)
    provider_key=${QWEN_API_KEY:-}
    provider_base_url=${QWEN_BASE_URL:-}
    provider_model=${QWEN_MODEL:-}
    ;;
  openai)
    provider_key=${OPENAI_API_KEY:-}
    provider_base_url=${OPENAI_BASE_URL:-}
    provider_model=${OPENAI_MODEL:-}
    ;;
  *)
    echo "FAIL unsupported teaching canary provider: $provider" >&2
    exit 2
    ;;
esac

if [ -z "$provider_key" ] || [ -z "$provider_base_url" ] || [ -z "$provider_model" ]; then
  echo "FAIL $provider teaching canary requires API key, base URL, and model" >&2
  exit 2
fi

cd "$repo_dir/backend"
./mvnw -q \
  -DRULEPILOT_ALLOW_PAID_CANARY=true \
  -Dtest=TeachingRichLessonPaidCanaryTest#plansAndPublishesACompleteMultiChapterLessonFromOneRealRulebookSlice \
  test
