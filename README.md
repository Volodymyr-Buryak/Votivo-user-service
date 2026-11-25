# User Service

Account blocking disables the identity and logs out Keycloak sessions. After Keycloak confirms
the status write, User Service commits enabled locally before full profile reconciliation, so
a failed reconciliation does not leave a confirmed block waiting for periodic synchronization.
Blocking preserves the profile, photo, votes and polls. Unblocking changes account access only.

The gateway checks `GET /internal/v1/users/{id}/access` on each authenticated Poll/Vote/Result
request. It requires `X-Internal-Auth` matching `INTERNAL_AUTH_SECRET` and returns 204 only for
an active profile (and a current admin role when admin=true). Missing configuration returns 503.
`issuedAt` is the verified JWT's iat. A durable sessions_revoked_at cutoff rejects old tokens even
after unblocking; a new login is required. Migration V5 also revokes tokens of previously disabled users.
Missing or wrong credentials return 401. Never expose this secret in browser configuration.
User/profile APIs separately enforce the current local status and mark revoked access with
`X-Account-Access: denied` so the browser can discard an otherwise unexpired JWT.

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

Profile access requires the `user` role without `admin`; the lightweight name header also allows
administrators. Both endpoints check account status.


## Profile photo

- `PUT /api/v1/users/me/photo`: multipart/form-data with a required `file` part; creates or replaces the photo and returns 204.
- `GET /api/v1/users/me/photo`: original binary with detected image/jpeg or image/png, Cache-Control: no-store; 404 if absent.
- `DELETE /api/v1/users/me/photo`: removes only the photo; returns 204 even when already absent.

All photo operations require the `user` role without `admin` (Bearer or DPoP).
The owner comes exclusively from JWT sub. Photos stay in PostgreSQL `user_photos.data` (bytea),
not Keycloak, and are preserved during identity synchronization. Account deletion removes the photo
in the same local transaction. Profile-row database locks serialize photo mutations and soft deletion
across API instances. Photo timestamps are separate from the identity projection's updatedAt.

Configure `app.photo` in application.yaml: `APP_PHOTO_MAX_SIZE` (default 5MB),
`APP_PHOTO_MAX_WIDTH_PIXELS` / `APP_PHOTO_MAX_HEIGHT_PIXELS` (4096), and
`APP_PHOTO_ALLOWED_FORMATS` (JPEG,PNG). The multipart file limit uses the same max-size;
`APP_PHOTO_MAX_REQUEST_SIZE` (6MB) must allow room for multipart overhead.
The validator identifies the format from bytes, checks dimensions before decoding and reads the first frame.
It does not sanitize or re-encode files. Invalid/empty images and excessive dimensions return 400,
excessive file/request size returns 413, and unsupported formats/content types return 415.
A rejected replacement leaves the previous photo intact. See docs/openapi.yaml for the full contract.


## Account deletion

`DELETE /api/v1/users/me` rejects administrator and disabled accounts with 403 and owners of any PUBLISHED polls
with 409 (`account.unfinished-polls`). DRAFT, CLOSED and ARCHIVED do not prevent deletion.
The current account is synchronized from Keycloak before checking eligibility. Poll Service uses
a persistent owner lock to check PUBLISHED polls and reserve the owner against concurrent publication.
Its internal endpoint is protected with `ACCOUNT_DELETION_SECRET`; it is not a browser API.

After reservation, one local transaction clears personal data and photo, sets deletedAt and persists
identity_deletion_pending. The identity management client sends enabled=false and POST logout;
it does not physically delete the Keycloak identity. A confirmed completion returns 204. If Keycloak
or the final marker update fails after the local commit, 202 is returned and the pending marker is
retried every 30 seconds (APP_ACCOUNT_DELETION_RETRY_INTERVAL), including after restart.
Synchronization never restores a deleted profile. The frontend clears the session after 204 or 202.

Set APP_ACCOUNT_DELETION_POLL_URL (localhost:18082 locally, poll-service:8082 in Compose) and the
same ACCOUNT_DELETION_SECRET in both services. Compose falls back to the existing INTERNAL_AUTH_SECRET.
Missing/mismatched credentials or a Poll Service outage reject deletion before local changes (503).
No distributed transaction is claimed: after an interrupted request, retry DELETE while the profile
is active. A failed local write releases the reservation only after confirming no local tombstone;
an unconfirmed storage failure keeps publication blocked. Administrative recovery may release an
orphan reservation via the internal DELETE endpoint only after verifying the profile is still active.


### Registering again after deletion

After disabling the identity and revoking its sessions, deletion replaces the Keycloak email with
`deleted-<old-user-uuid>@deleted.invalid` and releases username too. The placeholder is unique per
identity and never uses a real mail provider. The old identity stays disabled; emailVerified is reset,
first/last names and required actions are cleared. The pending marker is cleared only after this update.
Thus 204 confirms identifier release; with 202, the old email may remain occupied until the retry succeeds.
Migration V4 queues existing tombstones for the same cleanup without changing blocked/active accounts.

The original email can then register a completely new identity. No old UUID, roles, verification state
or poll ownership are transferred. The supplied Keycloak realm has verifyEmail=true, so the new account
must verify the email again using the normal registration flow. No verification email is sent to the
placeholder during deletion. Existing realm settings must retain verifyEmail=true.

Administrator accounts cannot self-delete, including through direct API calls. Remove them through Keycloak; the existing identity deletion synchronization still soft-deletes their local profiles.
