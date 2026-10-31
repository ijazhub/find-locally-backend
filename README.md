# FindLocally API

Java 21 / Spring Boot API. Build and verify with `mvn clean test`.

## Local PostgreSQL with Docker Compose

Compose and Spring Boot share `deploy/postgres.properties`. For a fresh checkout:

```powershell
Copy-Item deploy/postgres.properties.example deploy/postgres.properties
# Set POSTGRES_USER and POSTGRES_PASSWORD in deploy/postgres.properties.
docker compose up -d postgres
```

Run the API from the repository root so Spring imports the properties file.
PostgreSQL listens on `localhost:5433`, and its data persists in the Compose volume.
The local properties file is ignored by Git. Deployed applications can continue
using `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` instead. PostgreSQL initialization credentials apply
only when the data volume is empty; changing the file does not reset an existing
database user's password.

Customer/provider account separation, booking quotations, temporary holds and media
publication are documented in [the frontend handoff](docs/customer-provider-handoff.md).
V8 deliberately blocks ambiguous historical account roles; run the read-only
[account audit](docs/account-type-audit.sql) and approve remediation before rollout.
The handoff also describes the coordinated cutover and database audit events.

## Docker on EC2 behind Nginx

The root `Dockerfile` builds the executable JAR with Maven and Java 21, then
runs it as a non-root user in a Java 21 runtime image. Docker BuildKit is required.
Run tests before building; the image build skips tests.

On EC2, from this directory:

```shell
docker build -t findlocally-api:latest .
cp deploy/api.env.example deploy/api.env
chmod 600 deploy/api.env
# Edit deploy/api.env and replace all CHANGE_ME values before starting.
docker run -d --name findlocally-api --restart unless-stopped \
  --env-file deploy/api.env \
  --publish 127.0.0.1:8080:8080 \
  --log-opt max-size=10m --log-opt max-file=3 \
  findlocally-api:latest
docker logs --tail 100 findlocally-api
```

Use a reachable PostgreSQL host such as RDS. The existing `docker-compose.yml`
provides only a local development database. Its localhost URL cannot be used
unchanged inside the API container. Flyway runs automatically at startup.
Supply production database credentials and a unique base64 JWT secret through
the environment file; the application's checked-in defaults are for development.
Configure SMTP for password reset, and S3 for the deployed AWS resources.
Attach an EC2 instance role with the required S3 permissions. When using
IMDSv2 credentials from a bridged container, configure the instance metadata
response hop limit to 2 so the container can obtain credentials.

Use `deploy/nginx.conf.example` for Nginx running directly on the same EC2 host.
Set its domain and Angular build directory, configure HTTPS, then validate and
reload Nginx with `sudo nginx -t && sudo systemctl reload nginx`.
Set Angular's API base URL to `/api/v1`; Nginx preserves that path when proxying.
Set `APP_CORS_ALLOWED_ORIGINS` to the exact public frontend origin, including
the scheme. The example supports Angular client-side routes and media uploads.
Allow inbound ports 80/443 in the EC2 security group; the API is bound only to
host loopback. If Nginx runs in Docker instead, put both containers on the same
Docker network and proxy to `http://findlocally-api:8080`.

After startup, `curl -i http://127.0.0.1:8080/api/v1/account` should return an
HTTP 401 without a bearer token. Check container logs for successful application
startup and database migrations; a 401 alone does not verify database readiness.

The source contract is `src/main/resources/openapi/findlocally-openapi.yaml`.
Maven generates `com.findlocally.api.contract` interfaces and
`com.findlocally.api.model` DTOs under `target/generated-sources/openapi`.
Do not edit generated Java files. Controllers implement those interfaces,
inheriting endpoint mappings, request binding, defaults, and validation.
All controllers are mounted under `/api/v1` and grouped by the spec's tags.
Every application API requires a valid JWT bearer token except POST registration,
login, refresh, forgot-password, and reset-password. Refresh validates its
request-body refresh token; password reset validates a single-use email token.
S3 bucket, region and public media URL settings are listed under `app.media` in
`application.yaml`; AWS credentials come from the AWS SDK credential chain.

The dependency flow is controller -> service -> repository -> DbHelper ->
NamedParameterJdbcTemplate. Services and repositories are constructor injected.
All non-assistant controllers have service and repository implementations.
Assistant operations remain intentionally unimplemented and return HTTP 501.
Business onboarding saves all six steps, previews drafts, and publishes profiles.

Business onboarding saves the business, onboarding progress, services, availability,
media references and verification status directly in PostgreSQL. Submission marks
the profile ACTIVE in the same transaction; it creates no JSON snapshot or onboarding
outbox event. There is no SQS client, publisher, queue configuration or dependency.
Booking transition/quotation audit events remain database-only records.

Customer and business APIs cover discovery, availability, bookings, carts,
reviews, messaging, notifications, media, profiles, services, and dashboards.
Controllers specify the successful response codes from the contract.

`DbHelper` accepts `SqlParameterSource` (for example `MapSqlParameterSource`)
and provides `update`, `getRows` (class or RowMapper), `getSingleField`,
`insertWithoutKey`, and `insertWithKey`. Writes return affected-row counts;
key inserts return a typed key such as UUID or Long and require exactly one
inserted row. Single-field reads return null for no row or SQL NULL and reject
multiple rows. Use named SQL placeholders such as `:providerId`.
Put multi-statement transaction boundaries on service methods when implementing
business logic.

Runtime startup uses PostgreSQL and Flyway with the settings in
`src/main/resources/application.yaml`. Tests mock JDBC and disable migrations,
so the default suite runs without a database. Opt-in persistence tests use PostgreSQL.
Tests verify API operation coverage, endpoint registration, representative HTTP
responses, database helper edge cases, ownership, and complete workflows.

`PUT /api/v1/cart/items/{itemId}` replaces an owned cart item's provider, service,
start time, and fulfilment before checkout. Omitted optional values are cleared.

`POST /api/v1/media/uploads` accepts multipart fields `purpose` and `file`.
The backend inspects the file, streams it to S3 and returns its `uploadId` and
`processingState`. `UPLOADED` describes transfer completion; only `READY` media
can be published. The frontend
does not upload to S3 separately. Reuse that ID in profile, gallery or onboarding
requests. The previous JSON upload-slot format is no longer accepted. Messaging
uses REST; the live-message broker and `/ws` endpoint have been removed.

See [workflow and deployment notes](docs/remaining-services.md) and
[authentication configuration](docs/account-auth-catalog.md). The full HTTP
workflow test uses real Flyway migrations and triggers and mocks external object
storage and email delivery. Run it against a dedicated test database with:

```shell
mvn verify -Dremaining.postgres.tests=true -Dservices.postgres.tests=true -Dbookings.postgres.tests=true -Dbusiness.postgres.tests=true -Dworkflows.postgres.tests=true -Dspring.datasource.url=jdbc:postgresql://localhost:5433/findlocally_test -Dspring.datasource.username=sa -Dspring.datasource.password=YOUR_TEST_PASSWORD
```
