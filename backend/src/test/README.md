# Интеграционные тесты PostgreSQL

`DatabaseSchemaTest`, `SecurityIntegrationTest`, `OnboardingIntegrationTest` (подключение
компании: заявки, роли, личные ссылки, гонки) и `ApprovalIntegrationTest` (маршрут и решения: этапы, возврат,
гонки решений) запускаются только при заданных
`SCHEMA_TEST_URL` и `SCHEMA_TEST_PASSWORD`; обычный `mvn test` без них помечает эти тесты
как skipped. Тесты очищают таблицы, поэтому запускать их можно только на одноразовой базе.

Из каталога `backend` тест можно запустить на чистом PostgreSQL 16 так:

```powershell
$env:SCHEMA_TEST_PASSWORD = [guid]::NewGuid().ToString("N")
$env:MINIO_ACCESS_KEY = "schema-test-access"
$env:MINIO_SECRET_KEY = "schema-test-secret"
docker compose -p issue14-schema -f src/test/resources/db/compose.yaml up -d --wait
$port = (docker compose -p issue14-schema -f src/test/resources/db/compose.yaml port postgres 5432).Split(":")[-1]
$env:SCHEMA_TEST_URL = "jdbc:postgresql://127.0.0.1:$port/schema_test"
mvn test
docker compose -p issue14-schema -f src/test/resources/db/compose.yaml down -v
```
