# FindLocally: demo data and user journeys

This seed predates exclusive account types and quotation negotiation. Review the
[current contract and migration handoff](customer-provider-handoff.md) before using
the onboarding/booking journey; existing roles require the read-only audit.

## Demo environment

Seeded on 22 October 2026 in local PostgreSQL `localhost:5433/findlocally`, using the connection referenced by `console_1.sql`. The three existing users and three existing businesses were preserved. This guide describes the implemented API and intended screen flow; frontend screens have not been inspected.

All demo accounts use password **`DemoLocal@2026!`**. These credentials are for local development only.

| Persona | Email | Starting point |
| --- | --- | --- |
| Asha, primary customer | asha@demo.findlocally.test | Saved providers, following, history, unread messages, cart |
| Rohan, customer | rohan@demo.findlocally.test | Cancelled booking history |
| Nisha, customer | nisha@demo.findlocally.test | Declined booking history |
| Meera, makeup owner | makeup@demo.findlocally.test | Demo Glow Studio, Indiranagar |
| Kavya, bakery owner | home-baker@demo.findlocally.test | Demo Crumb Bakery, Koramangala |
| Arjun, delivery owner | delivery@demo.findlocally.test | Demo Quick Delivery, HSR Layout |
| Farah, catering owner | caterer@demo.findlocally.test | Demo Feast Catering, Jayanagar |
| Dev, tailor owner | tailor@demo.findlocally.test | Demo Stitch Studio, Whitefield |
| Priya, onboarding owner | onboarding@demo.findlocally.test | Demo Priya Cakes: ABOUT saved, LOCATION next |

All businesses are in **Bengaluru**. All amounts use **INR paise**: 55000 means INR 550. Availability uses **Asia/Kolkata**.

### Data included

| Records | Count |
| --- | ---: |
| Demo users | 9 |
| Active demo businesses / draft businesses | 5 / 1 |
| Services | 15 |
| Customer default addresses | 3 |
| Weekly opening-hour rows | 35 |
| Blocked dates | 5 |
| Studio notes | 5 |
| Simulated verified badges | 4 |
| Bookings | 30 |
| Reviews | 5 |
| Saved providers / follows | 5 / 5 |
| Conversations / messages | 5 / 10 |
| Notifications | 15 |
| Cart items | 1 |

Each active business has two COMPLETED bookings, one AWAITING_CONFIRMATION, one CONFIRMED, one CANCELLED and one DECLINED. Asha owns the completed, pending and confirmed bookings. One completed booking per provider has a review; the other is available for the review journey.

Dates are relative to the **first seed execution**: completed bookings yesterday and two days ago, pending tomorrow at 10:00, confirmed in two days at 10:00, cart in three days at 11:00, blocked date in fourteen days. Businesses open 09:00–18:00 daily. Reruns preserve existing dates and edits; update stale booking/cart dates through normal app operations when reusing later.

## Run or rerun the seed

The seed is separate from Flyway so startup never adds demo accounts automatically.

1. Apply application migrations V1–V7.
2. Open [scripts/seed-demo.sql](../scripts/seed-demo.sql) in the PostgreSQL console connected to the local `findlocally` database and execute the entire script.
3. Alternatively, from the API directory, use Java 21 and the locally installed JDBC driver:

```bat
java -cp C:\Users\user\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar scripts\SeedDemo.java
```

The Java utility reads datasource settings from `src/main/resources/application.yaml` and permits only the configured localhost URL. The SQL checks the database name. Every demo entity uses a deterministic UUID derived from `md5('findlocally-demo-v1:<entity>:<index>')::uuid`. Primary-key conflicts are skipped. Existing demo edits are preserved; this is an additive seed, not a reset. A conflicting email or other constraint causes the whole DO block to roll back rather than overwrite an existing account.

Refresh the SQL console result grid to see the inserted businesses. Useful queries:

```sql
SELECT b.id, b.business_name, b.status, c.slug, b.city, u.email
FROM businesses b
JOIN users u ON u.id = b.owner_user_id
LEFT JOIN categories c ON c.id = b.category_id
WHERE u.email LIKE '%@demo.findlocally.test'
ORDER BY b.business_name;

SELECT b.id, p.business_name, b.status, b.starts_at, b.service_name
FROM bookings b
JOIN businesses p ON p.id = b.business_id
WHERE b.note = 'Fictional demo booking'
ORDER BY p.business_name, b.starts_at;
```

## Session and API conventions

Start the application using its normal run configuration. The default base URL is `http://localhost:8080/api/v1`. Every path below is relative to `/api/v1`.

Log in using `POST /auth/login`:

```json
{"email":"asha@demo.findlocally.test","password":"DemoLocal@2026!"}
```

Keep the returned accessToken and send `Authorization: Bearer <accessToken>` on all application requests, including discovery. Keep the refreshToken for `POST /auth/refresh`; refresh rotates tokens, so replace both stored values. Access tokens last 15 minutes. Use separate sessions or browser profiles for customer and owner walkthroughs.

Provider, service, booking and conversation IDs come from list responses or the SQL queries above. Do not substitute names for IDs. For booking creation and checkout, send a new UUID in `Idempotency-Key`. Retrying the identical operation uses the same key.

## Journey 1: customer discovers and books a provider

**Persona:** Asha. **Goal:** find a local service, evaluate it and request a booking.

1. **Login → home.** Log in and load `GET /users/me`, `GET /home` and `GET /categories`.
2. **Browse → filter.** Use `GET /providers` with Bengaluru and a supported category. Each of the five categories has an active demo provider.
3. **Open provider.** Use `GET /providers/{providerId}`, `/services`, `/profile`, `/reviews` and `/media`. Show description, fulfilment choices, prices, ratings and studio notes. Media is intentionally empty.
4. **Save → follow.** The five providers are already saved and followed for Asha. Explore `GET /users/me/saved` and `GET /users/me/following`. Toggle saving through `PUT/DELETE /users/me/saved/{providerId}`; toggle following through `PUT/DELETE /providers/{providerId}/follow`.
5. **Choose service → choose time.** Fetch `GET /providers/{providerId}/availability/calendar` and `GET /providers/{providerId}/availability` using the contract's service/date parameters. Select a future available slot. Tomorrow at 10:00 and the next day at 10:00 have live bookings; the blocked date returns no slots.
6. **Choose fulfilment → address.** Choose an option offered by the selected service. AT_CLIENT and DELIVERY require an address; Asha has a default Bengaluru address at `GET /users/me/addresses`.
7. **Review → submit.** Submit `POST /bookings` using the selected provider, service, start time, fulfilment and address where required. Send an idempotency key.
8. **Confirmation → tracking.** Expect AWAITING_CONFIRMATION. Load `GET /bookings`, `GET /bookings/summary` and `GET /bookings/{bookingId}`.

**Success:** the customer sees the request in upcoming bookings and the owner sees the same request.

**Alternative paths:** a taken slot returns 409; select a fresh slot. Invalid fulfilment, missing address or a past time must be corrected. Use `POST /bookings/{bookingId}/cancel` for cancellation; cancelled history is already available with Rohan.

## Journey 2: customer checks out a cart

**Persona:** Asha. **Goal:** book the seeded bakery offer.

1. **Cart.** Open `GET /cart`. The seeded cupcake box is scheduled for three days after seeding at 11:00, with DELIVERY.
2. **Edit.** Add through `POST /cart/items`, or replace an item through `PUT /cart/items/{itemId}`. Include the full desired provider/service/time/fulfilment values; omitted optional time and fulfilment are cleared.
3. **Address.** Confirm a default address exists. Checkout uses that address for DELIVERY and AT_CLIENT.
4. **Checkout.** Submit `POST /cart/checkout` with an idempotency key.
5. **Bookings.** Verify returned bookings and the updated cart.

**Success:** valid items become booking requests atomically. An invalid or unavailable item leaves the cart intact. Retrying the same successful checkout with the same key returns its original result.

## Journey 3: provider manages a booking

**Persona:** Kavya or any active owner. **Goal:** respond to requests and deliver the service.

1. **Login → dashboard.** Load `GET /business/dashboard/overview`, `GET /business/dashboard/schedule`, `GET /business/bookings` and `GET /business/earnings`. Use the seeded booking dates when choosing schedule bounds.
2. **Pending request.** Open the seeded AWAITING_CONFIRMATION request.
3. **Accept.** Submit `POST /business/bookings/{bookingId}/accept`. The booking becomes CONFIRMED.
4. **Customer check.** In Asha's session, refresh booking details and notifications.
5. **Deliver → complete.** Only after a confirmed booking's scheduled end, submit `POST /business/bookings/{bookingId}/complete`.
6. **Earnings.** Check earnings for the month of the booking's scheduled date. Existing completed demo bookings already supply earnings data.

**Alternative:** decline a future pending booking through `POST /business/bookings/{bookingId}/decline` with a reason. Owners can act only on their own bookings. Completing a future booking is rejected; seeded completed bookings allow immediate review demonstrations without waiting.

## Journey 4: customer reviews and owner replies

**Persona:** Asha, then the corresponding owner.

1. Open Asha's completed bookings and select one with no existing review.
2. Submit a rating and text with `POST /bookings/{bookingId}/review`.
3. Refresh provider reviews and the provider card. The database trigger updates rating_avg and review_count.
4. Log in as that provider owner and load `GET /business/reviews`.
5. Reply through `POST /business/reviews/{reviewId}/reply`.
6. Refresh the customer review list to see the reply.

**Success:** one review per owned, completed booking, visible with the provider response. Five seeded reviews demonstrate ratings; some have replies and some do not.

## Journey 5: customer and provider exchange messages

**Persona:** Asha and an owner.

1. Open `GET /conversations`. Five demo conversations are present.
2. Open `GET /conversations/{conversationId}/messages`. Each has a customer enquiry and an owner reply.
3. Send a message with `POST /conversations/{conversationId}/messages`.
4. In the other session, refresh the same conversation and reply.
5. Mark the conversation read with `POST /conversations/{conversationId}/read`.
6. Open `GET /notifications`; mark one with `POST /notifications/{notificationId}/read` or all with `POST /notifications/read-all`.

For a new provider conversation, use `POST /conversations`. Messaging uses REST polling. Only the customer and the business owner can access the conversation. Seeded notification links illustrate destinations; frontend route handling must match the frontend implementation.

## Journey 6: a new provider finishes onboarding

**Persona:** Priya. **Goal:** resume a saved draft and publish a business.

1. **Resume.** Log in as onboarding@demo.findlocally.test and call `GET /business/onboarding`. ABOUT is completed and LOCATION is next. Use `POST /business/onboarding` only for a fresh account without a business.
2. **ABOUT.** Review the saved business name, owner name and home-baker category. Save edits with `PUT /business/onboarding/steps/ABOUT`.
3. **LOCATION.** Save Bengaluru, neighbourhood, location, service radius and work mode through `PUT /business/onboarding/steps/LOCATION`.
4. **SERVICES.** Add at least one valid service, duration, INR price and fulfilment, plus an optional budget range, through the SERVICES step.
5. **AVAILABILITY.** Save a weekly opening schedule or appointment-only mode, plus optional blocked dates.
6. **WORK.** Save the WORK step. Optional avatar, cover and gallery uploads use `POST /media/uploads` with multipart purpose/file; attach returned upload IDs.
7. **VERIFICATION.** Use an appropriate test document upload owned by this account and save the VERIFICATION step according to the OpenAPI request schema. This requires functioning object storage. Do not use a real identity document for a demo.
8. **Preview → submit.** Load `GET /business/onboarding/preview`, then `POST /business/onboarding/submit`.
9. **Published.** Verify the active listing in discovery. Identity verification status remains a separate concern from publication.

**Success:** all six saved steps permit submission; the business becomes ACTIVE and its onboarding data stays in PostgreSQL. No JSON snapshot, onboarding outbox event, SQS queue or publisher is required.

## Journey 7: provider maintains their business

1. Open `GET /business/profile` and update through `PATCH /business/profile`.
2. Manage offers through `GET/POST /business/services`, `PUT/DELETE /business/services/{serviceId}` and the service-active endpoint defined in OpenAPI.
3. Update working hours or blocked dates with `GET/PUT /business/availability`.
4. Add real test media using the multipart upload flow and `POST /business/media`; remove an attachment through `DELETE /business/media/{mediaId}`.
5. Recheck discovery and availability as Asha.

Previously booked service names and prices remain booking snapshots. Soft-deleted services retain historical booking references.

## Journey 8: account and session management

1. Edit the customer profile at `PATCH /users/me`.
2. Create or update addresses through `POST /users/me/addresses` and `PUT /users/me/addresses/{addressId}`. Explicitly set isDefault when selecting the default.
3. Refresh tokens at `POST /auth/refresh`.
4. Log out through `POST /auth/logout`; the session's old access token stops working.
5. For password recovery, request `POST /auth/forgot-password` and use the emailed token at `POST /auth/reset-password`. This requires SMTP configuration. Successful reset revokes all sessions.

The .test email addresses are fictional and cannot receive real email. Demonstrate recovery using a controlled local mail sink or a separate deliverable test account.

## Boundaries and verification

- No S3 uploads, identity documents, email deliveries or queue events were fabricated by the seed. Gallery/upload tables remain untouched. Simulated verification badges are demo display data, not evidence of identity checks.
- Assistant operations currently return HTTP 501; exclude assistant recommendations and assistant booking from a working demo.
- There is no implemented payment collection journey in this API. Checkout creates bookings.
- SQL seeding bypasses application-service notifications; database audit triggers still apply. The messages and notifications included here are explicit fixtures. No external queue delivery is performed.
- Active demo profiles are relational fixtures; their onboarding JSON is not a complete replay of the wizard. Use Priya's draft for onboarding.
- The seed committed successfully and was rerun without duplicate demo records. Database checks covered counts, booking/service/customer relationships, address ownership, reviews, ratings, hours and overlap constraints. The BCrypt hash was generated and matched using the application's Spring Security dependency.
- No API process was listening at localhost:8080 during setup. Browser screens, HTTP login and end-to-end actions were not exercised.
- The source contract is [findlocally-openapi.yaml](../src/main/resources/openapi/findlocally-openapi.yaml); additional behavior is documented in [account-auth-catalog.md](account-auth-catalog.md) and [remaining-services.md](remaining-services.md).
