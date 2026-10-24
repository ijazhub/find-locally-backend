# Account, authentication, and catalog

## Runtime configuration

- `app.auth.jwt-secret`: Base64-encoded key containing at least 32 cryptographically random bytes. Configure the same key on every API instance. If omitted, the application generates an ephemeral signing key; restarting invalidates existing access tokens.
- `app.media.public-base-url`: public object-storage/CDN prefix used for avatar URLs.
- `app.bookings.time-zone`: business hours timezone, default `Asia/Kolkata`; shared with bookings.
- `app.catalog.slot-interval-minutes`: candidate slot spacing, default 30, allowed range 5–120.

Use environment variables or external configuration for credentials. Passwords are stored as BCrypt hashes; refresh tokens as SHA-256 hashes. Never put the JWT key in source control.

## Authentication behavior

POST /api/v1/auth/register accepts email, password, and optional name and returns 201 with an AuthResponse. POST /api/v1/auth/login accepts email and password and returns 200 with the same response (newUser is false). Both responses contain accessToken, refreshToken, expiresIn, and user. Send access tokens using Authorization: Bearer <accessToken>.

Emails are trimmed and case-insensitive. Passwords require at least 12 characters and at most 72 UTF-8 bytes. Five failed logins lock the account for 15 minutes. Unknown accounts, wrong passwords, and locked accounts return the same 401 message. Registration with an existing email returns 409. OTP endpoints and delivery services have been removed. No social sign-in is configured.

Set JWT_SECRET to a Base64-encoded random key of at least 32 bytes for a stable signing key across restarts and instances. Without it the existing development fallback generates an ephemeral key.

Flyway V5 preserves existing users and adds nullable password hashes. Legacy accounts cannot log in until a password is enrolled through a separately verified process; registration cannot overwrite or claim them. Existing duplicate emails (ignoring case and surrounding whitespace) must be resolved before V5 can create its unique index. The old OTP table is retained as historical data and is no longer used. Phone is optional for new accounts.

Access tokens expire after 15 minutes; refresh sessions expire after 30 days. Refresh rotates both tokens and revokes the previous session. Logout revokes the session identified by the access token. Every bearer request checks the session, so old access tokens stop working immediately after refresh or logout. User roles are loaded from the database.

JWT verification uses Spring Security's [resource server support](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), with explicit signature algorithm, issuer, audience, expiry, and session validation.

All `/api/**` endpoints require `Authorization: Bearer <accessToken>`, including home, categories, provider search, profiles, media, reviews, availability and Assistant endpoints. POST `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/forgot-password`, and `/api/v1/auth/reset-password` are exempt. Refresh validates the refresh token in its request body so expired access tokens can be renewed. Logout requires a valid access token. Browser CORS preflight is handled before authentication. Swagger documentation remains accessible. Messaging uses authenticated REST endpoints; the former live-message endpoint has been removed.

## Forgot password

1. POST `/api/v1/auth/forgot-password` with `{"email":"user@example.com"}`. The API returns an empty 202 response for unknown accounts, legacy accounts without passwords, throttled requests, and accepted email deliveries. Requests are limited to one per minute and five per hour per account.
2. The email links to the trusted configured frontend URL with `#token=<token>`. The frontend reset page must read `token` from the URL fragment and submit it to the API; this backend does not serve the reset page. The fragment keeps the token out of HTTP request URLs and Referer headers. The link expires after 15 minutes, and resending invalidates all previous links.
3. POST `/api/v1/auth/reset-password` with `{"token":"<email-token>","newPassword":"a strong new password"}`. A successful reset returns 204, invalidates all reset links, clears login failures, and revokes all existing access/refresh sessions. Sign in again; the reset endpoint does not issue tokens. Missing, expired, consumed, or email-mismatched tokens return the same 400 error. Password rules match registration, including the 72-byte BCrypt limit.

Flyway V6 stores only SHA-256 hashes of 256-bit random reset tokens. Raw tokens appear only in the reset email. Reset and login/refresh requests serialize on the user row so concurrent refreshes cannot preserve an old session through a reset.

Configure these environment variables for SMTP delivery:

| Variable | Purpose |
| --- | --- |
| `MAIL_HOST` | SMTP server hostname |
| `MAIL_PORT` | SMTP port, default 587 |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP credentials |
| `MAIL_FROM` | Sender email address accepted by the SMTP provider |
| `PASSWORD_RESET_URL` | Frontend reset page URL, default `http://localhost:4200/reset-password`; use HTTPS outside localhost |
| `MAIL_SMTP_AUTH` | SMTP authentication, default true |
| `MAIL_STARTTLS` | Enable and require STARTTLS, default true |

Missing delivery configuration returns 503 independently of account existence. Runtime SMTP delivery errors are logged without tokens or recipient addresses; the request still returns 202 and its token is invalidated so account existence is not disclosed through delivery errors. SMTP connect/read/write timeouts are five seconds. Email transport is mocked in automated workflow tests; configure and verify your real SMTP provider before enabling recovery in deployment.

## Account and catalog behavior

Profile PATCH skips omitted/null fields. Addresses use replacement semantics on PUT; `isDefault` must be explicitly true to make an address the default. Default changes lock the user row and retain the database's one-default constraint. Foreign address IDs return 404.

Catalog reads expose active providers and active, undeleted services. Saved/followed flags are personalized for the authenticated user. Price filters, price ordering, and starting prices use INR minor units, matching the application's budget schema. Text search treats input literally.

Availability returns future candidate slots fitting the entire service duration inside opening hours. Busy slots have `available: false`; blocked/closed dates return no slots. Calendar dates require at least one available slot. Appointment-only providers arrange times directly and do not advertise automatic slots. All live booking statuses are checked, including requests awaiting confirmation.

## Verification

`mvn test` runs the default tests. Add `-Dservices.postgres.tests=true` to run account/auth/catalog database tests. They use temporary copies of the schema, roll back all writes, and exercise email/password authentication. Existing database checks remain opt-in through `-Dbookings.postgres.tests=true` and `-Dbusiness.postgres.tests=true`.
