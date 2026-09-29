#!/usr/bin/env bash
# Выкладка на стенд. Запускать на сервере стенда, из каталога проекта:
#
#   ./deploy.sh              — выложить свежий main
#   ./deploy.sh <ref>        — выложить конкретный коммит или тег (откат)
#
# Делает ровно то же, что руками, но не даёт забыть половину: обновить код,
# пересобрать образы, дождаться, пока бэкенд ответит, и показать, что получилось.
set -euo pipefail

# Скрипт лежит в репозитории, который сам же и обновляет: git checkout переписал бы
# файл прямо во время выполнения, и bash дочитал бы уже новую версию с середины.
# Поэтому сразу продолжаем работу из копии во временном каталоге.
if [[ "${DEPLOY_RUNNING_FROM_COPY:-}" != "1" ]]; then
  copy="$(mktemp)"
  cp "$0" "$copy"
  chmod +x "$copy"
  DEPLOY_RUNNING_FROM_COPY=1 REPO_DIR="$(cd "$(dirname "$0")" && pwd)" exec "$copy" "$@"
fi

REF="${1:-origin/main}"
cd "${REPO_DIR:?}"

HEALTH_TIMEOUT_SECONDS=180

echo "== Стенд: $(hostname), каталог $(pwd)"
echo "== Сейчас выложено: $(git log --oneline -1)"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "!! В каталоге есть незакоммиченные изменения — выкладка остановлена." >&2
  echo "   Разберитесь с ними: git status" >&2
  exit 1
fi

echo "== Забираем код"
git fetch --prune origin
git checkout --quiet --detach "$REF"
echo "== Выкладываем: $(git log --oneline -1)"

echo "== Пересобираем и перезапускаем (может занять несколько минут)"
docker compose up -d --build

# Адрес берём у самого Compose, а не собираем заново: так учитываются BACKEND_PORT
# из .env и любые override-файлы, и проверка не расходится с публикацией порта.
HEALTH_URL="http://$(docker compose port backend 8080)/actuator/health"

echo "== Ждём, пока бэкенд ответит (до ${HEALTH_TIMEOUT_SECONDS} с)"
deadline=$(( SECONDS + HEALTH_TIMEOUT_SECONDS ))
until curl --silent --fail --max-time 5 "$HEALTH_URL" | grep -q '"status":"UP"'; do
  if (( SECONDS >= deadline )); then
    echo "!! Бэкенд не поднялся за ${HEALTH_TIMEOUT_SECONDS} с." >&2
    echo "   Логи:   docker compose logs --tail 50 backend" >&2
    echo "   Откат:  ./deploy.sh <прошлый коммит>" >&2
    exit 1
  fi
  sleep 5
done

echo
echo "== Готово. Выложено: $(git log --oneline -1)"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
echo
echo "Проверьте руками: https://documentics.ru — мини-приложение, /start в @t354_hakaton_max_bot — бот."
