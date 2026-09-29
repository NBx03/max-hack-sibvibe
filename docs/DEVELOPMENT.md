# Разработка и запуск вне Docker

## Бэкенд вне Docker и сертификат Минцифры

Bot API (`platform-api2.max.ru`) и GigaChat работают на сертификате Минцифры. В Docker-образе
он уже есть. Если запускаете бэкенд **из IDE на своей Java**, один раз добавьте корень в её
хранилище — команда из корня репозитория (Windows: терминал от имени администратора):

```bash
keytool -importcert -noprompt -cacerts -storepass changeit -alias russian-trusted-root-ca -file backend/certs/russian_trusted_root_ca.pem
```

Проверка: `keytool -list -cacerts -storepass changeit -alias russian-trusted-root-ca`.
Ставить сертификат в Windows или браузер не нужно — мини-приложение к этим адресам не обращается.

## Локальная аутентификация без MAX

Запустите backend с профилем `dev` и используйте заголовок
`Authorization: Dev <положительный maxUserId>`. Например:

```powershell
mvn -Dspring-boot.run.profiles=dev spring-boot:run
curl.exe -H "Authorization: Dev 123456" http://localhost:8080/api/v1/me
```

Для демо-песочницы дополнительно задайте `DEMO_MODE=true`. Без профиля `dev`
такой заголовок всегда получает 401; настоящий `MAX_BOT_TOKEN` локально не нужен.

## Бот

MAX Bot API — общий ресурс: два процесса не могут одновременно принимать
события. Отсюда порядок.

**На стенде (webhook)** — выставляется один раз в `.env` стенда:
- `MAX_BOT_UPDATE_MODE=webhook`;
- `MAX_BOT_WEBHOOK_URL=https://<домен мини-приложения>` — тот же адрес, на котором висит
  мини-приложение: nginx уже проксирует `/api` на backend, отдельный домен для бэкенда не нужен;
- `MAX_BOT_WEBHOOK_SECRET` — случайная строка 5–256 символов, только `A-Za-z0-9_-`
  (`openssl rand -hex 32`; **не** `openssl rand -base64` — в алфавите `+ / =`, подписка тогда
  молча не пройдёт).

Бэкенд подписывается на события сам при старте (`POST /subscriptions`). Проверить, что
подписка есть: `GET /subscriptions` (пример ниже) должен вернуть непустой список.

**Локально (polling)** — с настоящим `MAX_BOT_TOKEN` бэкенд запускает **только тот, кто
сейчас проверяет бота**; у всех остальных в `.env` — `MAX_BOT_ENABLED=false` (или пустой
`MAX_BOT_TOKEN`, работает так же). Пока стенд ещё не переведён на webhook, два процесса
с polling отбирают обновления друг у друга, и бот на стенде отвечает «через раз» — не по
багу, а потому что MAX отдаёт каждое обновление только одному подписчику. После перевода
стенда на webhook локальный polling перестаёт получать обновления вовсе — это ожидаемо:
подписка на webhook отключает Long Polling для бота (документация MAX).

**Никогда не делать локально:**
- `MAX_BOT_UPDATE_MODE=webhook` без крайней необходимости и без согласования — это снимет
  или перепишет подписку стенда, и бот на стенде замолчит для всех;
- `DELETE /subscriptions` с настоящим токеном — то же самое.

**Ручная проверка токена** (тем же токеном, что в `.env` стенда):

```bash
curl -H "Authorization: $MAX_BOT_TOKEN" https://platform-api2.max.ru/me
curl -H "Authorization: $MAX_BOT_TOKEN" https://platform-api2.max.ru/subscriptions
```

Первая команда подтверждает, что токен рабочий и показывает username бота; вторая — какой
режим сейчас реально активен на стороне MAX (пустой список — никто не подписан, значит бот
либо выключен, либо ждёт локального polling).
