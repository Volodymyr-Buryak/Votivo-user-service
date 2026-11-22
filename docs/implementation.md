# Реализация User Service

User Service — приложение Java 25 / Spring Boot 4.1.2 на Gradle. Keycloak остаётся источником идентификационных данных, состояния учётной записи и ролей. PostgreSQL хранит локальную проекцию для API и проверки отзыва доступа. Сервис не хранит пароли и не синхронизирует фотографии/аватары.

Контракт HTTP описан в [openapi.yaml](openapi.yaml), запуск — в [README](../README.md). OpenAPI — самостоятельный документ для текущей реализации, без автоматической генерации и гарантии синхронного обновления с кодом. В приложении нет springdoc dependency, Swagger UI и endpoint `/v3/api-docs`. Инфраструктура и настройка realm находятся в [репозитории backend](https://github.com/koliruslik/Votivo/tree/main/backend), подробности — в [Keycloak README](https://github.com/koliruslik/Votivo/blob/main/backend/keycloak/README.md).

## Слои приложения

Корневой пакет — `com.votivo.user_service`. Зависимости основного сценария проходят от `controller` через интерфейсы `service` и реализации `service.impl` к `repository` / `entity` и клиентам `integration.keycloak`.

| Пакет | Назначение |
|---|---|
| `controller` | `UserController` обслуживает собственный профиль, `AdminUserController` — административный список, чтение и смену статуса. UUID владельца берётся из JWT `sub`. |
| `service` | Интерфейсы `ProfileService`, `ProfileReader`, `ProfileWriter`, `UserSynchronizationService`. |
| `service.impl` | Оркестрация операций, чтение проекции, короткие транзакции записи и синхронизация с Keycloak. |
| `repository` | `UserProfileRepository`, Spring Data JPA; чтение активных профилей, поиск и получение активных UUID. |
| `entity` | `UserProfile` и enum `UserRole` со значениями `user`, `admin`. |
| `dto.request` | Валидируемые `UpdateProfileRequest`, `UserStatusRequest`, `UserSearchQuery`. |
| `dto.response` | `UserResponse`, `UserPageResponse`; JPA-сущность напрямую не возвращается. |
| `dto.keycloak` | `RemoteUser`, представление ответа Admin API. |
| `integration.keycloak` | Раздельные клиенты чтения и управления, получение и кеширование служебных токенов, HTTP transport. |
| `service.exception` | Ошибки профиля, доступа, конфликта и взаимодействия с identity provider. |
| `controller.advice` | `ApiExceptionHandler`: преобразование ошибок MVC и сервисов в `ProblemDetail`. |
| `security`, `security.config` | JWT/DPoP, авторизация, проверка локального состояния учётной записи, CORS. |
| `config` | Типизированные identity properties, регистрация конфигурации и RabbitMQ topology. |
| `messaging` | Обработка событий и сверка проекции при старте и по расписанию. |
| `service.support` | Общий singleton `UserLockManager`, используемый и управлением профилем, и синхронизацией. |

`ProfileServiceImpl` не держит транзакцию БД во время сетевых запросов. `ProfileReaderImpl` читает в `readOnly`-транзакции, `ProfileWriterImpl` выполняет короткие транзакции `upsert` и `softDelete`. `open-in-view=false`, схема проверяется через `ddl-auto=validate`.

`UserLockManager` содержит 256 полосовых `ReentrantLock`: одинаковый UUID всегда использует один lock, разные UUID могут попасть в одну полосу. Повторный захват допустим, поэтому оркестратор может вызвать синхронизацию под тем же lock. Это упорядочивает операции только внутри одного JVM; распределённой блокировки нет.

## Жизненный цикл профиля

### Чтение

`GET /api/v1/users/me` доступен роли `user` или `admin`. Административные маршруты требуют `admin`:

- `GET /api/v1/admin/users` — список локальных неудалённых профилей;
- `GET /api/v1/admin/users/{id}` — профиль по UUID;
- `PATCH /api/v1/admin/users/{id}/status` — включение/блокировка.

Чтение по UUID сначала обращается к локальной проекции. Если записи никогда не было, сервис делает адресное чтение Keycloak, синхронизирует профиль и повторяет локальное чтение. Если UUID уже сохранён как удалённый, возвращается `404` без такого fallback. Для собственного выключенного профиля предусмотрен отказ в доступе; фильтр безопасности также проверяет известную локальную блокировку/удаление.

Список не инициирует полную синхронизацию. `search` обрезается по краям, приводится к нижнему регистру и ищется как подстрока в email, имени или фамилии; `%`, `_` и `!` экранируются. Дополнительный фильтр `enabled` необязателен. Пагинация с нуля: `page=0`, `size=20`, размер от 1 до 100, сортировка по UUID `id`. Ответ содержит `items`, `page`, `size`, `totalElements`, `totalPages`.

### Изменение собственного профиля

Несмотря на метод `PATCH /api/v1/users/me`, запрос обязан содержать все три поля: непустые `firstName`, `lastName` (до 255 символов) и корректный непустой `email` (до 320). Неизвестные JSON-поля отклоняются. Роли, `enabled` и `emailVerified` этим DTO не изменяются.

Под lock пользователя выполняются следующие шаги:

1. Проверяется текущий собственный профиль.
2. Management-клиент отправляет `PUT` в Keycloak с именем, фамилией и email.
3. При изменении email отправляется `emailVerified=false`; при `email-as-username=true` одновременно обновляется `username`.
4. Sync-клиент перечитывает авторитетные данные и effective realm roles, затем обновляет локальную проекцию.
5. Если email изменён либо перечитанный email ещё не подтверждён, вызывается `execute-actions-email` с `["VERIFY_EMAIL"]`.

SMTP настраивается в Keycloak. Повторный PATCH с тем же неподтверждённым email снова пытается отправить письмо. Сбой письма или синхронизации после успешного Keycloak `PUT` может вернуть `503`, хотя удалённое изменение уже сохранено. Общей транзакции и автоматического отката Keycloak нет.

### Блокировка и удаление

Администратор передаёт `{ "enabled": false }` или `true`; значение обязательно. Отключить собственную учётную запись этим маршрутом нельзя. Сначала меняется Keycloak, затем синхронизируется проекция. При блокировке дополнительно вызывается Keycloak `logout`. Даже если синхронизация упала, logout всё равно предпринимается; исходная ошибка сохраняется, а ошибка logout добавляется как suppressed. При успешной синхронизации ошибка logout также завершает запрос ошибкой.

`DELETE /api/v1/users/me` сначала удаляет пользователя в Keycloak, затем мягко удаляет локальную запись и отвечает `204`. UUID остаётся в БД: email и имена очищаются, роли становятся пустыми, `emailVerified=false`, `enabled=false`, выставляется `deletedAt`. `createdAt` сохраняется. Повторное обнаружение пользователя при синхронизации может восстановить проекцию, сбросив `deletedAt`.

## Данные и транзакции

Миграция `src/main/resources/db/migration/V1__create_users.sql` создаёт две таблицы. Flyway применяет миграцию, Hibernate проверяет соответствие JPA mapping. Уже применённые миграции изменять нельзя.

| `user_profiles` | Тип / смысл |
|---|---|
| `id` | UUID, первичный ключ и Keycloak user ID. |
| `email` | Nullable `VARCHAR(320)`. |
| `first_name`, `last_name` | Nullable `VARCHAR(255)`. |
| `email_verified`, `enabled` | Обязательные boolean. |
| `created_at` | Nullable timestamp with time zone, upstream время создания. |
| `updated_at` | Обязательное локальное время изменения содержимого. |
| `synced_at` | Обязательное время последней синхронизации. |
| `deleted_at` | Nullable маркер мягкого удаления. |
| `version` | Обязательный BIGINT, JPA `@Version` для optimistic locking. |

`user_roles` содержит `user_id` (FK на `user_profiles`) и `role` с ограничением `user` / `admin`; пара образует первичный ключ. Роли загружаются через effective/composite realm role mappings Keycloak, остальные роли отбрасываются. В realm роль `admin` должна включать `user`; JWT converter также явно даёт администратору обе application authorities.

При неизменившемся снимке обновляется `syncedAt`, а `updatedAt` остаётся прежним. Отсутствующие upstream имена/email и время создания остаются nullable. Ответ API содержит только `id`, `email`, `firstName`, `lastName`, `emailVerified`, `enabled`, `roles`, `createdAt`, `updatedAt`: `syncedAt`, `deletedAt`, `version` наружу не выдаются.

## Интеграция Keycloak

`KeycloakAdminClient` используется только для чтения: список пользователей, пользователь по UUID, effective realm roles. `KeycloakUserManagementClient` выполняет профильные `PUT`, смену enabled, logout, удаление и отправку verify-email action. У каждого клиента свой `KeycloakTokenProvider` и отдельные credentials.

Служебные токены получаются через `client_credentials`, кешируются с запасом до истечения (`min(30 секунд, expires_in / 10)`). После HTTP `401` токен инвалидируется и запрос повторяется один раз. Неоднозначно завершившиеся записи автоматически не повторяются. HTTP connect timeout — 5 секунд, read timeout — 15 секунд. Management `404` преобразуется в `404` API, `409` — в конфликт; прочие ошибки identity provider — в `503`.

Для нового realm используется backend snapshot v5. Для уже существующего realm startup import ничего не обновляет: изменения клиентов, ролей, audience и event listener применяются через Admin Console/API.

| Клиент | Настройки и полномочия |
|---|---|
| `user-service-sync` | Confidential, service accounts enabled; browser/password flows и DPoP выключены. Service account: только `realm-management/view-users`, `query-users`. |
| `user-service-management` | Отдельный confidential service account; browser/password flows и DPoP выключены. Роли `view-users`, `manage-users`, отдельный secret. |
| `user-service` | Bearer-only resource client, целевая audience API. |
| `votivo-frontend` | Audience mapper должен включать `user-service` в access token; существующие PKCE/DPoP settings сохраняются. |

Для обоих служебных клиентов используются default scopes `service_account`, `roles`, `basic`, `kc-admin-api`. Sync account не получает `manage-users` / `realm-admin`; существующий административный клиент для него не используется. Секреты приложения должны совпадать с настройками соответствующих клиентов Keycloak. Локального endpoint логина и password grant в User Service нет.

## События и восстановление проекции

При включённой синхронизации сервис объявляет durable topic exchanges `identity.users.exchange`, `identity.users.dlx`, durable очереди `user-service.identity.users`, `user-service.identity.users.dlq`. Основная очередь имеет dead-letter exchange; DLQ связана с DLX шаблоном `#`.

Binding основной очереди: `KK.EVENT.CLIENT.<realm>.SUCCESS.*.*` и `KK.EVENT.ADMIN.<realm>.SUCCESS.*.*`; точки в имени realm убираются. Для `votivo` пример client route — `KK.EVENT.CLIENT.votivo.SUCCESS.<client>.UPDATE_EMAIL`.

- Client events: `REGISTER`, `UPDATE_PROFILE`, `UPDATE_EMAIL`, `VERIFY_EMAIL`, `DELETE_ACCOUNT`. JSON должен содержать совпадающий `type` и UUID `userId`.
- Admin route имеет суффикс `<resourceType>.<operationType>`. JSON содержит совпадающий `operationType` и `resourcePath`. Путь `users/<uuid>/...` запускает адресную синхронизацию.
- Глобальные изменения `ROLE`, `REALM_ROLE`, `CLIENT_ROLE`, `GROUP`, `REALM_ROLE_MAPPING`, `CLIENT_ROLE_MAPPING`, `REALM_ROLE_COMPOSITE`, `CLIENT_ROLE_COMPOSITE`, `GROUP_MEMBERSHIP` могут запускать полную сверку, если путь не адресует конкретного пользователя.

Событие служит сигналом: содержимое профиля из payload не записывается, данные перечитываются из Keycloak. Нерелевантные события игнорируются; некорректный релевантный payload приводит к ошибке обработки. Всего предусмотрены три попытки с паузами 1 и 2 секунды. После исчерпания сообщение отклоняется без requeue и попадает по dead-letter route в DLQ. После устранения причины сообщения можно переотправить вручную.

`IdentityReconciliation` запускает сверку на `ApplicationReadyEvent` и с fixed delay `PT1H` по умолчанию. Список Keycloak читается страницами по 100; для обычных пользователей дополнительно читаются актуальный профиль и роли. Service accounts пропускаются. После успешного полного обхода отсутствующие в списке локальные UUID проверяются адресным запросом. Только подтверждённый `404` вызывает soft delete: неполная выдача списка, transport error, `401/403/5xx` сами по себе не означают удаление. Если получение ролей вернуло `404`, выполняется повторная проверка существования пользователя.

Ошибки фоновой сверки логируются, последующая сверка повторяет работу. Потерянные broker events и изменения ролей между сверками дают задержку обновления локальной проекции. Нужно проверить совместимость backend Keycloak Rabbit provider и доставку событий; сохранить существующие event listeners и добавить `keycloak-to-rabbitmq`.

## Безопасность HTTP

API — stateless OAuth2 Resource Server. Проверяются подпись по JWKS, точный issuer `iss`, срок действия, наличие `exp`, audience `user-service` и канонический UUID в `sub`. JWT authorities берутся только из lowercase `realm_access.roles`: `user` и `admin`.

Используется встроенная поддержка DPoP Spring Security. Для bound token требуется корректный DPoP proof; предъявление такого токена через Bearer отклоняется. Proof проверяется штатным verifier, включая привязку к запросу/токену и replay validation. In-memory replay cache ограничен 100000 записями и 1000 запросами на ключ. Его состояние локально для JVM.

Перед authorization локально известный профиль проверяется на `enabled`, отсутствие `deletedAt` и, для admin routes, наличие локальной роли `admin`. Неизвестная локальная запись на этом этапе пропускается к дальнейшей JWT авторизации и обработчику: это позволяет первый fallback. Поэтому отзыв роли и внешняя блокировка начинают действовать после обновления проекции; offline JWT сам по себе не опрашивает Keycloak на каждый запрос. Ошибка БД при проверке состояния даёт `503`.

CORS разрешает настроенные origins, методы `GET`, `PATCH`, `DELETE`, `OPTIONS`, заголовки `Authorization`, `Content-Type`, `DPoP`; наружу открыт `WWW-Authenticate`. CSRF, form login, HTTP Basic и request cache отключены. Маршрут `/actuator/health` разрешён правилами security, но actuator dependency и реализованного health endpoint в этом приложении нет.

## Ошибки API

Ошибки возвращаются как `application/problem+json`: `400` для некорректного запроса/валидации, `401` для аутентификации, `403` для запрета доступа, `404` для отсутствующего профиля, `409` для конфликта аккаунта, `503` для недоступного identity provider, `500` для неожиданной ошибки. Validation response содержит map `errors` по полям. Security errors записываются отдельно от MVC advice, с `Cache-Control: no-store`; authentication entry point добавляет `WWW-Authenticate: DPoP, Bearer`.

## Конфигурация и запуск

Spring Boot не загружает `.env` автоматически: переменные нужно экспортировать в PowerShell/IDE либо передать контейнеру. Значения credentials ниже намеренно заменены placeholders.

| Переменные | Значение / назначение |
|---|---|
| `SERVER_PORT` | По умолчанию HTTP 8080; backend Compose публикует 18081. |
| `SPRING_DATASOURCE_URL` | Host: `jdbc:postgresql://localhost:15432/user_db`; Docker: `jdbc:postgresql://postgres:5432/user_db`. |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | `<database-user>`, `<database-password>`. |
| `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT` | Host: `localhost`, `15675`; Docker: `rabbitmq`, `5672`. |
| `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, `SPRING_RABBITMQ_VIRTUAL_HOST` | `<broker-user>`, `<broker-password>`, `/`. |
| `APP_IDENTITY_BASE_URL`, `APP_IDENTITY_REALM` | Host: `http://localhost:18080`; Docker: `http://keycloak:8080`; realm `votivo`. |
| `APP_IDENTITY_CLIENT_ID`, `APP_IDENTITY_CLIENT_SECRET` | `user-service-sync`, `<sync-secret>`; secret соответствует backend `KEYCLOAK_USER_SERVICE_SYNC_SECRET`. |
| `APP_IDENTITY_SYNC_ENABLED` | `true`; `false` отключает listeners/topology/background reconciliation и обязательность sync credentials, но не превращает API в локальный identity provider. |
| `APP_IDENTITY_PAGE_SIZE`, `APP_IDENTITY_RECONCILE_INTERVAL` | `100`, `PT1H`, положительные значения. |
| `APP_IDENTITY_MANAGEMENT_CLIENT_ID`, `APP_IDENTITY_MANAGEMENT_CLIENT_SECRET` | `user-service-management`, `<management-secret>`; соответствует backend `KEYCLOAK_USER_SERVICE_MANAGEMENT_SECRET`. |
| `APP_IDENTITY_MANAGEMENT_EMAIL_AS_USERNAME` | `true`, соответствует текущему realm. |
| `APP_SECURITY_ISSUER_URI` | `http://localhost:18080/realms/votivo`; должен точно совпасть с issuer токена. |
| `APP_SECURITY_JWK_SET_URI` | `http://localhost:18080/realms/votivo/protocol/openid-connect/certs`; в Docker допустим внутренний адрес Keycloak. |
| `APP_SECURITY_AUDIENCE`, `APP_SECURITY_ALLOWED_ORIGINS` | `user-service`, `http://localhost:4200`; origin меняется на фактический frontend. |

Replay limits заданы в `application.yaml` как `app.security.dpop-replay-cache-size=100000` и `app.security.dpop-max-requests-per-key=1000`.

Из каталога `backend` инфраструктурного репозитория:

```powershell
Copy-Item .env.example .env
# Заполнить локальные credentials, SMTP и secrets обоих клиентов.
docker compose up -d postgres rabbitmq keycloak
```

Из корня User Service, после экспорта переменных окружения:

```powershell
$env:APP_IDENTITY_CLIENT_SECRET = '<sync-secret>'
$env:APP_IDENTITY_MANAGEMENT_CLIENT_SECRET = '<management-secret>'
.\gradlew.bat bootRun
```

Для сборки отдельного checkout в backend Compose переменная `USER_SERVICE_BUILD_CONTEXT` должна указывать на его каталог с Dockerfile; затем выполняется `docker compose up -d --build user-service`. Пользовательский порт API в этом варианте — 18081.

## Проверка и границы реализации

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

Сохранённые XML-отчёты `build/test-results/test/TEST-*.xml` содержат 149 тестов в 15 suites, без failures, errors и skipped. Это результат имеющихся отчётов; новая сборка при подготовке этого документа не запускалась. Тесты покрывают контроллеры, безопасность, Keycloak clients, события, сервисы и repository/context integration. Context tests используют H2 в режиме совместимости PostgreSQL, настоящую Flyway migration и JPA schema validation; identity synchronization и запуск Rabbit listener/admin отключены. Им не нужны live infrastructure и secrets. H2 не заменяет проверку с настоящими PostgreSQL, Keycloak, SMTP и RabbitMQ.

Согласованность между Keycloak, локальной БД и брокером — eventual consistency. Успешная удалённая запись с последующим сбоем не откатывается; клиент может получить ошибку после изменения аккаунта. Пропущенные события и поздно обновлённые роли исправляются reconciliation. Общий lock manager и DPoP replay cache работают только в одном JVM: перед несколькими API replicas потребуются согласованные механизмы упорядочивания и защиты от replay.
