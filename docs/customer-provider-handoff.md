# Customer/provider implementation and frontend handoff

## Findings and sequence
The existing contract generates interfaces and DTOs into target; no generated files are edited. Existing controller ? service ? repository ? DbHelper flow remains. Booking creation owns idempotency and snapshots; cart checkout reuses it. PostgreSQL exclusion protects slots. Services already soft-delete. Conversations already have a unique customer/business pair and participant checks. Profile models distinguish ownerName/businessName; social profile needs businessName added. Upload transfer completion currently does not establish playable video readiness.

Implementation order: publish this proposal and source OpenAPI additions; add fail-safe migration and audit; enforce persisted account types; extend existing booking paths with negotiation, fixed deadlines, history and expiry; extend media visibility metadata; extend dashboard; generate/compile and run tests.

## Endpoint reuse/change matrix (base /api/v1)
| Existing endpoint | Change |
|---|---|
| POST /auth/register | Optional accountType CUSTOMER (legacy default) or BUSINESS; immutable thereafter |
| /auth/login, /auth/refresh, /users/me | user.accountType and onboardingStatus; roles retained as a deprecated singleton compatibility field |
| /business/onboarding | BUSINESS only; no longer assigns a role |
| /providers/{id}/profile, /services, /media | Reuse for Bio/My Work and Services tabs; authenticated viewing retained |
| /business/profile, /services, /availability, /media | Owned BUSINESS resources only |
| POST /conversations | Reopens existing pair; customer initiates Chat with ownerName; shared thread access remains participant based |
| POST /bookings, POST /cart/checkout | CUSTOMER only; optional proposedPrice, otherwise listed price; fixed temporary hold |
| /business/bookings/{id}/accept, /decline, /complete | Reuse; accept only original customer proposal, never an outstanding quote |
| POST /business/bookings/{id}/quotations | New versioned quotation revision, Idempotency-Key required |
| POST /bookings/{id}/quotation-response | New explicit ACCEPT/REJECT of quotationId + version; rejection reason required; Idempotency-Key required |
| GET /bookings/{id} | Both participants; snapshots, quotation history, deadline and allowedActions |
| /business/dashboard/overview, /schedule, /earnings | Distinguish confirmed work and temporary holds; totals are booking value, not money collected |
| POST /media/uploads | Transfer status UPLOADED retained; processingState and inspected dimensions/duration added |
| PUT /business/media/{mediaId} | New owned metadata/publication update; captions, format and an owned ready cover upload |

## Contract and compatibility
Brand update: API metadata and assistant labels now use FindLocally. Badge enum
`TRUSTED_BY_NIVA` is replaced by `TRUSTED_BY_FINDLOCALLY`; frontend enum/label
mappings must use the new value. V10 migrates stored badges while preserving their
business association and award timestamp. Historical migrations remain unchanged.

Statuses add AWAITING_CUSTOMER_APPROVAL and EXPIRED; old names remain. Clients must handle both before rollout. No customer counteroffer loop. Provider quotation revisions supersede older ones. Chat has no pricing/state side effects. Booking.total remains legacy listed-price snapshot for compatibility; use agreedPrice for committed value. listedPrice, proposedPrice and agreedPrice are distinct Money objects. Money.amount is nonnegative int64 minor units, currency uppercase ISO code (INR supported by existing marketplace). Example proposedPrice: {"amount":45000,"currency":"INR"}. Quotation: {"price":{"amount":48000,"currency":"INR"}}. Response: {"quotationId":"<uuid>","version":2,"decision":"ACCEPT"}; REJECT also supplies reason. New mutations require UUID Idempotency-Key; reuse with different payload gives 409. Old quote or version, expired deadline, unavailable slot or invalid transition gives 409. Invalid inputs give 400; unauthenticated 401; account type violation 403; inaccessible resources 404. Existing Problem response is retained.

Timestamps are RFC3339 offset timestamps, compared as instants and stored timestamptz. Dates and weekly opening hours are evaluated in configured app.bookings.time-zone (Asia/Kolkata default); intervals are [start,end). Pages remain zero-based, default size 20, max 50. allowedActions are server-computed hints; mutations always reauthorize and revalidate. Terminal bookings expose no negotiation actions.

## Safe account audit and rollout
Do not infer or silently remove roles from historical accounts. Run the read-only docs/account-type-audit.sql against a reviewed database snapshot. Accounts with multiple/no roles, business owners lacking BUSINESS, or BUSINESS users with customer bookings require an operator-reviewed mapping and product decision about retained historical customer access. Preserve all bookings/conversations. Back up the mapping and revoke sessions after approved remediation. V8 deliberately aborts if roles are ambiguous or business ownership contradicts the sole role. It never assigns/removes existing roles. New registration selects one type; no role switching. No migrations or data changes were executed against the configured application database. The read-only audit attempt on localhost:5433 was refused because the server was unreachable; actual account counts and remediation remain pending. console_2.sql is unrelated and must not be run.

## Proposed product defaults requiring agreement
Request response/total hold: 24 hours, capped at service start; quotation response: 12 hours, capped at the original hold deadline. Revisions never move that original deadline. Expiry sweep every 30 seconds; creation releases due holds transactionally before testing availability. Limits: images/documents 10 MiB, videos 100 MiB; JPEG/PNG/WebP, MP4/WebM, PDF only for ID documents. Videos (including reels) allow 600 seconds; dimensions allow 8192 per side and 40 million pixels. These are proposed defaults requiring agreement, configured under app.bookings and app.media (environment variable examples are in deploy/api.env.example).

Inspection runs synchronously before the backend streams to S3. ImageIO verifies supported image bytes; ffprobe/ffmpeg inspect and fully decode videos/WebP with a 60-second bound per process. Supported video codecs are H.264, VP8, VP9 and AV1. Missing tools, invalid bytes, oversized dimensions, excessive duration or decode failures produce FAILED, never playable published content. Docker installs ffmpeg; native deployments must install/configure both executables. The upload response distinguishes transfer UPLOADED from processing READY/FAILED. Failed files may be attached as drafts for owner visibility but must be replaced to publish. There is no client-supplied READY override or automatic transcoding.

Media format STANDARD/REEL is separate from PHOTO/VIDEO type; reels require VIDEO. Processing PENDING/READY/FAILED and publication DRAFT/PUBLISHED are independent. Only READY + PUBLISHED content is returned to customers; draft/unfinished owner responses have null url. Newly attached READY media defaults to PUBLISHED for compatibility unless DRAFT is requested. Existing videos are quarantined as PENDING/DRAFT and require inspected re-upload before publication. Existing photos remain published only when linked to an owned completed gallery upload. Historic object URLs are not revoked by this API migration. coverUploadId must identify an owned READY COVER or GALLERY_PHOTO upload. PUT metadata preserves omitted values; an empty caption clears the caption.

## Screen action contract
| State | Customer actions | Provider actions |
|---|---|---|
| AWAITING_CONFIRMATION before deadline | CANCEL | ACCEPT_PROPOSAL, ISSUE_QUOTATION, DECLINE |
| AWAITING_CUSTOMER_APPROVAL before deadline | CANCEL, ACCEPT_QUOTATION, REJECT_QUOTATION | ISSUE_QUOTATION, DECLINE |
| CONFIRMED before service start | CANCEL | none |
| CONFIRMED after service end | none | COMPLETE |
| Terminal or due pending deadline | none | none |

Chat remains available separately to participants and does not change this table. Buttons must use allowedActions and refresh on 409. A quotation response identifies both quotationId and version, never merely a price. Provider decline now requires a nonblank reason in a required JSON body; cancellation reason stays optional. Quote rejection requires a nonblank reason. Existing business service/onboarding reads may retain 404 when no business/draft exists. Creation and management guards otherwise use 403 for the wrong account type; foreign owned resources use 404.

## Examples (response excerpts)
Registration: `{"email":"provider@example.com","password":"a long unique password","accountType":"BUSINESS","name":"Asha"}`.

Authentication user (also returned by token refresh and GET /users/me):
```json
{"id":"123e4567-e89b-12d3-a456-426614174000","name":"Asha","accountType":"BUSINESS","onboardingStatus":"NOT_STARTED","roles":["BUSINESS"],"businessId":null}
```

Customer request:
```json
{"providerId":"123e4567-e89b-12d3-a456-426614174001","serviceId":"123e4567-e89b-12d3-a456-426614174002","startsAt":"2026-11-03T10:00:00+05:30","fulfilment":"AT_STUDIO","proposedPrice":{"amount":45000,"currency":"INR"}}
```

Booking after a quotation:
```json
{"status":"AWAITING_CUSTOMER_APPROVAL","listedPrice":{"amount":50000,"currency":"INR"},"proposedPrice":{"amount":45000,"currency":"INR"},"agreedPrice":null,"holdExpiresAt":"2026-11-02T00:00:00Z","responseDeadline":"2026-11-01T12:00:00Z","allowedActions":["CANCEL","ACCEPT_QUOTATION","REJECT_QUOTATION"],"quotations":[{"id":"123e4567-e89b-12d3-a456-426614174003","version":1,"price":{"amount":48000,"currency":"INR"},"state":"CURRENT","createdAt":"2026-11-01T00:00:00Z","expiresAt":"2026-11-01T12:00:00Z"}]}
```

Validation/conflict responses use application/problem+json, for example `{"type":"about:blank","title":"Conflict","status":409,"detail":"Stale quotation: reload the current quotation before responding","instance":"/api/v1/bookings/<id>/quotation-response","traceId":"<uuid>"}`. Bean validation failures may additionally include errors with field/message. Do not parse detail text as an error enum.

Dashboard today counts confirmed bookings overlapping the local date. pendingHolds and awaitingCustomerApproval count live holds, not confirmed appointments. needsReply and quotationsAwaitingApproval provide at most five booking previews; use paginated /business/bookings with the corresponding status for full lists. Earnings.total/daily and overview.monthEarnings retain legacy INR fields. Earnings.totals and overview.monthBookingValues group completed agreed value by currency; completedBookings counts all currencies. No currency conversion or payment collection is performed.

## Cutover and consumers
Retire/drain old writers during the coordinated backend migration: older binaries do not populate the new required booking columns. Deploy frontend enum/account-selection support alongside the backend; old CUSTOMER-to-business onboarding now returns 403. Existing booking-create idempotency fingerprints are preserved when proposedPrice is omitted, including cart checkout. Historical cached responses remain immutable and can lack new fields; refetch booking details for current state/actions.

Onboarding now persists only business/onboarding records in PostgreSQL; it does not create a BUSINESS_ONBOARDED JSON snapshot or outbox event. The SQS dependency and publisher have been removed. Booking/quotation outbox rows remain database-only audit records, alongside transactional notifications and transition history. No external consumer or queue setup is required. Existing migrations and historic outbox records are retained.
