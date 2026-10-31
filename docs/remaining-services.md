# Remaining API implementations

The [customer/provider handoff](customer-provider-handoff.md) supersedes the older
account-role, booking negotiation and media-publication behavior described here.

All non-assistant service/repository skeletons are implemented. Assistant operations retain their 501 responses.

## Behavior to review

- Reviews require an owned, completed booking and are limited to one review per booking. The existing database trigger maintains provider rating totals. Business owners can reply to their own reviews.
- Saved/following operations are idempotent. Public lists show active providers only.
- Conversations are shared by a customer and business owner. Optional booking links must belong to that customer and provider. Only participants can read, send, or mark messages read; read operations exclude the caller's own messages.
- Business owners can accept/decline future pending bookings and complete confirmed bookings after their scheduled end. Repeating a successful transition returns the current booking.
- Checkout is atomic and idempotent. Every item needs a start time and fulfilment. AT_CLIENT/DELIVERY items use the customer's default address because the checkout contract has no address parameter. `PUT /api/v1/cart/items/{itemId}` replaces an owned item's provider, service, start time and fulfilment while retaining its ID. Omitted optional time/fulfilment fields are cleared. Updates validate active offers, supported fulfilment and the currency of the other cart items. A failed update or checkout preserves the cart and rolls back bookings and idempotency records. Carts are limited to 50 items and one currency.
- Service creation, updates, toggles and deletion acquire the same business-row lock as onboarding step saves and submission. Booking offer reads lock the business before its service to keep prices and availability stable and maintain consistent lock ordering.
- Dashboard schedule bounds are inclusive local dates, with a maximum range of 366 days. Earnings use completed bookings, their scheduled date, and INR minor units.

## Storage

Configure `app.media.bucket`, `app.media.region` (default `ap-south-1`), and `app.media.public-base-url`. The S3 SDK uses its standard AWS credential chain; use an IAM role or external credentials.

These settings are now explicitly listed in `application.yaml`:

| Environment variable | Property | Value to provide |
| --- | --- | --- |
| `S3_BUCKET` | `app.media.bucket` | Your existing S3 bucket name |
| `AWS_REGION` | `app.media.region` | Bucket's AWS region; defaults to `ap-south-1` |
| `S3_PUBLIC_BASE_URL` | `app.media.public-base-url` | Public media base URL, e.g. `https://media.example.com/` |
| `S3_REQUEST_TIMEOUT_SECONDS` | `app.media.request-timeout-seconds` | Backend S3 request timeout, default 120 seconds |

Provide credentials through an IAM role, an AWS profile, or `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (plus `AWS_SESSION_TOKEN` for temporary credentials). These are read directly by the AWS SDK and do not need duplicate credential properties. For a local named profile, use the extensionless file `C:\Users\user\.aws\credentials` with a `[findlocally]` section and set `AWS_PROFILE=findlocally` in the application's run environment. A file called `credentials.txt` is not read as the default credentials file. No bucket or credentials are provisioned by this configuration. Upload/attachment operations return 503 when required storage configuration is missing or AWS rejects the request.

POST `/api/v1/media/uploads` now accepts `multipart/form-data` with `purpose` and a binary `file`. The backend validates the file's declared MIME type and actual received size, streams the bytes to S3 with overwrite protection, and marks the upload UPLOADED only after S3 succeeds. It returns `uploadId`, `status`, `contentType`, and `sizeBytes`. There is no presigned URL or separate browser-to-S3 step. This replaces the previous JSON upload-slot request and is a breaking client change.

```shell
curl.exe http://localhost:8080/api/v1/media/uploads -H "Authorization: Bearer YOUR_ACCESS_TOKEN" -F "purpose=ID_DOCUMENT" -F "file=@C:/path/passport.pdf;type=application/pdf"
```

Use the returned `uploadId` in the existing avatar, onboarding or business-gallery request. Client filenames do not determine object keys. Files are streamed from multipart temporary storage rather than loaded into a large byte array. The servlet request limits are 100 MiB per file and 101 MiB per request; purpose-specific size limits below are enforced by the service. Missing/empty files and unsupported MIME types return 400; oversized files return 413. AWS failure status/code/request ID are logged without credentials, document keys or full SDK messages.

Images allow JPEG, PNG, and WebP up to 10 MiB. Gallery videos allow MP4 and WebM up to 100 MiB. ID documents additionally allow PDF, with a 10 MiB limit. Identity-document keys use `private/`; public images/videos use `public/`. Configure the bucket/CDN so private keys are never served publicly.

Completed backend uploads are immediately usable by account avatars, onboarding and business media, subject to ownership and purpose validation. Legacy PENDING uploads still use S3 HEAD verification when attached. If the database transaction rolls back after a successful upload, the backend attempts to delete that request's new object. Grant the application `s3:PutObject` and `s3:GetObject` on the appropriate bucket prefixes, plus `s3:DeleteObject` for rollback cleanup. If cleanup is unavailable, storage lifecycle/manual cleanup may be needed. Gallery deletion removes the attachment rather than deleting a potentially referenced storage object. External storage is mocked in database tests; real credentials, bucket policies and successful cloud uploads require environment verification.

## Messages

Messaging uses the authenticated REST endpoints for conversations, sending, reading and marking messages read. Fetch the message list periodically for updates. WebSocket/STOMP support, its broker, and the `/ws` endpoint have been removed. Persisted chat messages and notifications remain available through REST.

## Error responses

`GlobalExceptionHandler` extends Spring's [exception handling support](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html). Errors use `application/problem+json` with status, title, detail, instance, and a trace ID. Validation errors include field/message entries without rejected values. Framework HTTP headers such as Allow are retained.

Domain exceptions retain useful status/reason messages. Integrity conflicts return 409; transient database failures return 503. Unexpected failures return a generic 500 with a trace ID, without SQL, credentials, or stack traces. Security entry points and servlet error dispatch use the same response format.

## Tests

Run the command in [README](../README.md) against a dedicated PostgreSQL test database. The `remaining`, `services`, `bookings`, and `business` test switches enable tests using temporary tables and rolled-back writes.

`-Dworkflows.postgres.tests=true` additionally enables `WorkflowPersistenceTests`. This applies all real Flyway migrations on startup, then rolls back its workflow data. Use a dedicated test database for this mode. The HTTP test registers separate customer and owner accounts, saves all six onboarding steps, verifies uploads, publishes the provider, schedules a cart item, checks out with idempotent replay, exchanges messages, accepts and completes the booking, posts a review, checks the rating trigger and earnings, revokes the customer session on logout, and resets the customer's password through an emailed token. Object storage and email delivery are mocked; database queries, migrations, triggers, controllers, validation and JWT security are real.
