# Customer/provider backend completion report — 2026-10-30

## Onboarding update

Onboarding now persists only to PostgreSQL. Its JSON snapshot/outbox creation, SQS SDK, client, publisher and queue settings have been removed. Booking audit rows and in-app notifications remain database-only; no queue consumer is required. The verification below records the earlier implementation; current removal checks are reported separately.

Removal verification: **100 tests passed, 0 failures/errors; 44 unrelated opt-in database tests skipped**. This includes all three onboarding persistence tests and the complete real-HTTP workflow, both asserting that onboarding creates no outbox entry. SeedDemo.java compiles and reads the shared PostgreSQL properties/environment settings; the seed was not executed. The disposable test database was stopped afterward.

## Delivered
Extended the existing APIs and source OpenAPI contract, without editing generated Java. The findings, implementation sequence, endpoint reuse/change matrix, examples, action contract and coordinated rollout requirements are in [the frontend handoff](customer-provider-handoff.md).

- Exclusive immutable CUSTOMER/BUSINESS types, selected during registration; account type and onboarding status in registration, login, refresh and account responses. Provider ownership checks remain independent of account type. Shared booking details and conversations retain participant checks.
- V8 refuses ambiguous historical role assignments without altering them. New deferred constraints require exactly one account type; application and database guards restrict business/booking creation. [Read-only audit SQL](account-type-audit.sql) and remediation strategy are supplied.
- Booking proposal/listed/agreed money snapshots, versioned quotation history, explicit acceptance/rejection, stale-version conflicts, fixed original hold deadlines, transactional slot protection, scheduled/lazy expiry, idempotency and transition history. Cart checkout continues to use the booking path and defaults to a listed-price proposal. Legacy omitted-proposal idempotency hashes are retained.
- Profile owner/business identity remains separate. Gallery supports STANDARD/REEL presentation independently of PHOTO/VIDEO, captions, inspected dimensions/duration, owned covers, processing and publication states. Invalid/unfinished/draft content has no playable published API URL. Existing videos are quarantined pending inspected replacement; services retain historical booking snapshots when changed/deactivated.
- Dashboard separates requests, outstanding quotations, pending holds, confirmed local-date work and unread conversations. Completed value uses agreed amounts and groups currencies. Transactional notifications/outbox events cover quotation issuance, acceptance, rejection and booking/quotation-hold expiry.
- Docker includes native media inspection tools; the Nginx example allows for inspection plus S3 transfer. No deployment was performed.

## Verification
Final Maven test run: **150 tests, 0 failures, 0 errors, 0 skipped**. All opt-in PostgreSQL suites were enabled. PostgreSQL 18.6 ran in a disposable cluster under target on port 15433, separate from the configured application database. At that time external queue publishing was disabled; object storage/email were mocked in HTTP workflows. The temporary server was stopped after verification.

The real V1–V9 migration chain applied successfully from an empty schema. A separate disposable database verified that dual roles block V8 without changing those roles, followed by successful migration after explicit fixture remediation. It also verified exclusive/immutable roles and rejection of an account with no role.

Coverage includes competing overlapping requests, simultaneous quotation acceptance, simultaneous duplicate request replay, stale/revised quotations, wrong participants/types, explicit rejection reasons, immutable pricing/service/address snapshots, expiry and slot release before/after the sweep, offset-normalized and legacy idempotency, local-midnight interval boundaries, cart checkout/replay, dashboard agreed value, gallery ownership, publication gating, image bytes/dimensions, failed video inspection, HTTP request validation and generated API mapping coverage. Existing registration-to-onboarding-to-checkout-to-messaging-to-completion/review/logout/password-reset HTTP workflow passes.

Verification command (supply dedicated test database credentials and an empty separate database for migration.audit.url):
```powershell
mvn test -Dapp.bookings.expiry-delay-ms=3600000 `
  -Dnegotiation.postgres.tests=true -Dremaining.postgres.tests=true `
  -Dservices.postgres.tests=true -Dbookings.postgres.tests=true `
  -Dbusiness.postgres.tests=true -Dworkflows.postgres.tests=true `
  '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:15433/postgres?currentSchema=negotiation_verification' `
  '-Dmigration.audit.url=jdbc:postgresql://127.0.0.1:15433/account_migration_audit' `
  -Dspring.datasource.username=test_owner -Dspring.datasource.password=
```
NegotiationPersistenceTests can bootstrap its isolated schema using the same port with a URL ending in /postgres (without currentSchema). Run it once before the full suite on an entirely empty test cluster; the older temporary-table suites need the PostgreSQL extensions installed by V1. Standard unit/HTTP tests run with plain mvn test. This machine used its existing Maven cache via -Dmaven.repo.local; no dependencies were downloaded.

## Remaining rollout decisions and limits
The configured application database at localhost:5433/findlocally was unreachable (connection refused). The requested read-only audit was attempted through the IDE database connector, but actual dual-role counts/account mappings could not be obtained. No existing application roles were assigned or removed. Run the supplied audit and approve remediation before V8 can be rolled out.

Product agreement is still required for the proposed 24-hour total/request hold, 12-hour quotation response, 30-second sweep, 10 MiB image/document and 100 MiB video limits, 600-second video duration, and dimension/pixel caps. All are described in the handoff with configuration names. Confirm the policy for preserving historical customer access when selecting a legacy business account type; participants can currently retain historical detail/thread access.

Frontend rollout must handle the new statuses/account-selection flow and required decline reasons. Drain older backend writers during migration. Booking audit records are database-only; onboarding produces no external event.

Successful native FFmpeg video decoding, the Docker image build, and real cloud S3 delivery were not exercised in this Windows environment. Native tools were unavailable locally; fail-closed behavior and valid image processing were tested, and READY video metadata/publication was tested against PostgreSQL. Validate real video decoding in the image/native deployment environment before release. No payments or other excluded product features were added. The referenced console SQL was read only and never executed.
