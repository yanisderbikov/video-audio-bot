#!/bin/sh
set -eu
: "${TELEGRAM_BOT_TOKEN:?Set TELEGRAM_BOT_TOKEN}"
: "${TELEGRAM_BASE_URL:=http://localhost:8081}"
case "${1:-}" in
  logout-cloud)
    curl --fail-with-body -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/logOut"
    ;;
  polling)
    curl --fail-with-body -sS -X POST "${TELEGRAM_BASE_URL}/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook" \
      --data-urlencode 'drop_pending_updates=false'
    ;;
  webhook)
    : "${TELEGRAM_WEBHOOK_URL:?Set TELEGRAM_WEBHOOK_URL}"
    : "${TELEGRAM_WEBHOOK_SECRET:?Set TELEGRAM_WEBHOOK_SECRET}"
    curl --fail-with-body -sS -X POST "${TELEGRAM_BASE_URL}/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
      --data-urlencode "url=${TELEGRAM_WEBHOOK_URL}" \
      --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
      --data-urlencode 'allowed_updates=["message"]' \
      --data-urlencode 'drop_pending_updates=false'
    ;;
  *) echo 'Usage: sh scripts/telegram-setup.sh logout-cloud|polling|webhook' >&2; exit 2 ;;
esac
