# User Service

Java 25 / Spring Boot 4.1.2 service providing authenticated profile and administration APIs backed by a local projection of Keycloak users. Keycloak remains the identity source. No photo/avatar data is synchronized.

## Local setup

1. In the backend checkout, copy `.env.example` to `.env`, set the credential placeholders (including `KEYCLOAK_USER_SERVICE_SYNC_SECRET`), and start PostgreSQL, RabbitMQ and Keycloak with `docker compose up -d postgres rabbitmq keycloak`.
2. For a new Keycloak realm, use the backend's v5 initial import. For an existing `votivo` realm, configure the dedicated `user-service-sync` client and its service-account roles through Admin Console/API; changing the import version does not update an existing realm.
3. Export the variables from this checkout's `.env.example` into the shell or IDE environment. `.env` is an example convention and is not automatically loaded by Spring Boot. Set `APP_IDENTITY_CLIENT_SECRET` to the same secret as the Keycloak client. Use backend RabbitMQ credentials if overridden there.
4. Run `./gradlew bootRun` (PowerShell: `.\gradlew.bat bootRun`). The HTTP port is 8080 unless `SERVER_PORT` is set.

Host defaults use PostgreSQL `localhost:15432/user_db` (`user`/`user`), RabbitMQ `localhost:15675` (`guest`/`guest`, vhost `/`), Keycloak `http://localhost:18080`, realm `votivo`. Docker uses internal addresses `postgres:5432`, `rabbitmq:5672`, `keycloak:8080`. Backend Compose publishes User Service on port 18081. To build the standalone checkout instead of its submodule, set `USER_SERVICE_BUILD_CONTEXT=C:/Users/Vovan/Desktop/Votivo-user-service` in backend `.env`, then run `docker compose up -d --build user-service`. That path must contain the completed service and its Dockerfile.

## Synchronization

The confidential client `user-service-sync` uses client credentials with DPoP disabled. Its service account needs only `realm-management` roles `view-users` and `query-users`; do not use the existing administrative client. Client scopes include `service_account`, `roles`, `basic` and `kc-admin-api` so the Admin API token has its role claims and audience. The client secret is required when `APP_IDENTITY_SYNC_ENABLED=true` (default). Set it to `false` to disable identity network synchronization without credentials.

RabbitMQ identity events trigger authoritative reads from Keycloak. Startup and periodic reconciliation page through users (`APP_IDENTITY_PAGE_SIZE=100`, `APP_IDENTITY_RECONCILE_INTERVAL=PT1H` by default) so missed events can be repaired. Roles are limited to lowercase `user` and `admin`; effective admin membership includes user. Deletes retain a soft-deleted profile instead of removing its UUID.

The local projection stores UUID, email, first/last names, email verification, enabled status, upstream creation time, local update/sync times, deletion marker, roles and an optimistic-lock version. Upstream creation time is nullable when Keycloak omits it. Unknown profile fields stay nullable. Flyway creates `user_profiles` and `user_roles`; Hibernate validates the schema at startup. Do not edit applied migrations. Profile API requests use Bean Validation and return ProblemDetail errors. API access requires a valid issuer, user-service audience and application role; DPoP-bound tokens require a matching proof.

Listener retries are bounded to three attempts with exponential backoff, and exhausted messages are rejected without requeue so the declared dead-letter route receives them. Inspect the dead-letter queue and fix the cause before replaying; reconciliation is the fallback for missed notifications. The service declares its messaging topology at startup. With an existing realm, preserve existing event listeners and enable `keycloak-to-rabbitmq` as described in the backend Keycloak README.

## Verification

Run `.\gradlew.bat build` (or `./gradlew build`). Context tests use H2 in PostgreSQL compatibility mode, run the real Flyway migration, validate JPA mappings, and disable Rabbit listener/admin startup and identity synchronization. They need no live infrastructure or client secret. H2 coverage does not replace a PostgreSQL/RabbitMQ/Keycloak integration check.

Profile changes require a separate user-service-management client with view-users/manage-users. Set APP_IDENTITY_MANAGEMENT_CLIENT_SECRET to match KEYCLOAK_USER_SERVICE_MANAGEMENT_SECRET in backend configuration. Keep the synchronization client read-only. Existing realms require the v5 client and audience changes from the backend Keycloak README; startup import does not update them. Replay protection currently uses a bounded per-JVM cache and assumes a single API replica.

## Profile API

- `GET /api/v1/users/me/header`: only firstName and lastName (nullable for incomplete profiles).
- `GET /api/v1/users/me`: full profile, including read-only email.
- `PATCH /api/v1/users/me`: both firstName and lastName required, nonblank, max 255. Email and other unknown fields are rejected with 400.
- Email is changed through Keycloak only; name updates do not modify email/username/emailVerified or send email.

Both GET endpoints require the same user/admin authorization and account-status checks.
