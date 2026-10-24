-- =============================================================================
-- Niva: initial schema (derived from niva-openapi.yaml)
-- PostgreSQL 13+ (gen_random_uuid is built in). Money is stored as paise (bigint).
-- Enumerations are TEXT + CHECK so they are easy to extend with a later migration.
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- needed for the double-booking exclusion constraint

-- ---------- helpers ----------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------- accounts & auth --------------------------------------------------
CREATE TABLE users (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       varchar(20)  NOT NULL UNIQUE,
    name        varchar(120),
    email       varchar(254),
    avatar_url  text,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_users_updated BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_roles (
    user_id  uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role     varchar(20) NOT NULL CHECK (role IN ('CUSTOMER', 'BUSINESS')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE otp_codes (            -- POST /auth/otp/request, /auth/otp/verify
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        varchar(20) NOT NULL,
    code_hash    text        NOT NULL,        -- never store the plain code
    attempts     int         NOT NULL DEFAULT 0,
    expires_at   timestamptz NOT NULL,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_phone_created ON otp_codes (phone, created_at DESC);

CREATE TABLE refresh_tokens (       -- POST /auth/refresh, /auth/logout
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);

CREATE TABLE addresses (            -- /users/me/addresses
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label          varchar(40),
    line1          varchar(200) NOT NULL,
    line2          varchar(200),
    neighbourhood  varchar(100),
    city           varchar(100) NOT NULL,
    state          varchar(100),
    pincode        varchar(10)  NOT NULL,
    lat            double precision,
    lng            double precision,
    is_default     boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_addresses_user ON addresses (user_id);
CREATE UNIQUE INDEX uq_addresses_one_default ON addresses (user_id) WHERE is_default;

CREATE TABLE uploads (              -- POST /media/uploads (pre-signed URL flow)
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose       varchar(20) NOT NULL CHECK (purpose IN ('AVATAR', 'COVER', 'GALLERY_PHOTO', 'GALLERY_VIDEO', 'ID_DOCUMENT')),
    content_type  varchar(100) NOT NULL,
    size_bytes    bigint      NOT NULL CHECK (size_bytes > 0),
    object_key    text        NOT NULL UNIQUE,         -- key in object storage (S3)
    status        varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'UPLOADED')),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_uploads_user ON uploads (user_id);

-- ---------- catalog ----------------------------------------------------------
CREATE TABLE categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug        varchar(50)  NOT NULL UNIQUE,
    name        varchar(100) NOT NULL,
    tint        varchar(20),
    sort_order  int          NOT NULL DEFAULT 0
);

-- One row per provider. A row is created as DRAFT when onboarding starts, so the
-- wizard's columns are nullable until the business is ACTIVE.
CREATE TABLE businesses (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id         uuid        NOT NULL UNIQUE REFERENCES users(id),
    status                varchar(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED')),
    current_step          varchar(15) NOT NULL DEFAULT 'ABOUT'
                          CHECK (current_step IN ('ABOUT', 'LOCATION', 'SERVICES', 'AVAILABILITY', 'WORK', 'VERIFICATION', 'DONE')),
    completed_steps       text[]      NOT NULL DEFAULT '{}',
    category_id           uuid REFERENCES categories(id),
    business_name         varchar(120),
    owner_name            varchar(120),
    headline              varchar(120),
    bio                   varchar(400),
    note                  varchar(300),               -- "A note from ..."
    tags                  text[]      NOT NULL DEFAULT '{}',
    city                  varchar(100),
    neighbourhood         varchar(100),
    lat                   double precision,
    lng                   double precision,
    service_radius_km     numeric(5,1),
    work_mode             varchar(20) CHECK (work_mode IN ('STUDIO', 'TRAVELS_TO_CLIENT', 'BOTH')),
    by_appointment_only   boolean     NOT NULL DEFAULT false,
    avatar_url            text,
    cover_image_url       text,
    started_year          int,                        -- drives "years working locally"
    budget_min_amount     bigint CHECK (budget_min_amount >= 0),
    budget_max_amount     bigint CHECK (budget_max_amount >= 0),
    verification_status   varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    rating_avg            numeric(3,2) NOT NULL DEFAULT 0,   -- kept in sync by trg_reviews_rating
    review_count          int          NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_business_complete CHECK (
        status = 'DRAFT' OR (business_name IS NOT NULL AND category_id IS NOT NULL AND city IS NOT NULL AND neighbourhood IS NOT NULL)
    )
);
CREATE TRIGGER trg_businesses_updated BEFORE UPDATE ON businesses FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_businesses_browse ON businesses (city, category_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_businesses_name   ON businesses (lower(business_name)) WHERE status = 'ACTIVE';

CREATE TABLE business_badges (
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    badge        varchar(20) NOT NULL CHECK (badge IN ('VERIFIED', 'TOP_RATED', 'IDENTITY_CHECKED', 'TRUSTED_BY_NIVA')),
    awarded_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, badge)
);

CREATE TABLE business_verifications (   -- onboarding step "Get verified"
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id             uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    id_type                 varchar(20) NOT NULL CHECK (id_type IN ('AADHAAR', 'PAN', 'PASSPORT', 'DRIVING_LICENCE', 'VOTER_ID')),
    id_document_upload_id   uuid REFERENCES uploads(id),   -- the document stays in object storage; no ID numbers are stored here
    status                  varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    reject_reason           text,
    submitted_at            timestamptz NOT NULL DEFAULT now(),
    reviewed_at             timestamptz
);
CREATE INDEX idx_verifications_business ON business_verifications (business_id);

CREATE TABLE services (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id       uuid         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    name              varchar(80)  NOT NULL,
    description       varchar(200),
    duration_minutes  int          NOT NULL CHECK (duration_minutes >= 5),
    price_amount      bigint       NOT NULL CHECK (price_amount >= 0),   -- paise
    currency          char(3)      NOT NULL DEFAULT 'INR',
    fulfilment        text[]       NOT NULL DEFAULT '{AT_STUDIO}',       -- AT_CLIENT, AT_STUDIO, PICKUP, DELIVERY
    active            boolean      NOT NULL DEFAULT true,
    sort_order        int          NOT NULL DEFAULT 0,
    deleted_at        timestamptz,                                       -- soft delete: bookings keep pointing at it
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_fulfilment CHECK (fulfilment <@ ARRAY['AT_CLIENT', 'AT_STUDIO', 'PICKUP', 'DELIVERY']::text[])
);
CREATE TRIGGER trg_services_updated BEFORE UPDATE ON services FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_services_business ON services (business_id) WHERE deleted_at IS NULL;

CREATE TABLE business_hours (           -- weekly pattern (AvailabilityInput.weekly)
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    day          varchar(3)  NOT NULL CHECK (day IN ('MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN')),
    is_open      boolean     NOT NULL DEFAULT true,
    open_time    time,
    close_time   time,
    PRIMARY KEY (business_id, day),
    CHECK (NOT is_open OR (open_time IS NOT NULL AND close_time IS NOT NULL AND close_time > open_time))
);

CREATE TABLE business_blocked_dates (   -- AvailabilityInput.blockedDates
    business_id  uuid NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    blocked_on   date NOT NULL,
    PRIMARY KEY (business_id, blocked_on)
);

CREATE TABLE business_media (           -- "My work" gallery
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id       uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    upload_id         uuid REFERENCES uploads(id),
    type              varchar(5)  NOT NULL CHECK (type IN ('PHOTO', 'VIDEO')),
    url               text        NOT NULL,
    thumbnail_url     text,
    caption           varchar(80),
    duration_seconds  int,
    sort_order        int         NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_business ON business_media (business_id, sort_order);

CREATE TABLE studio_notes (             -- "Studio notes" tab
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    text         text        NOT NULL,
    posted_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_studio_notes_business ON studio_notes (business_id, posted_at DESC);

-- ---------- saved & following ------------------------------------------------
CREATE TABLE saved_businesses (
    user_id      uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, business_id)
);

CREATE TABLE follows (
    user_id      uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, business_id)
);
CREATE INDEX idx_follows_business ON follows (business_id);

-- ---------- bookings & cart --------------------------------------------------
CREATE TABLE bookings (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id       uuid         NOT NULL REFERENCES users(id),
    business_id       uuid         NOT NULL REFERENCES businesses(id),
    service_id        uuid         NOT NULL REFERENCES services(id),
    status            varchar(25)  NOT NULL DEFAULT 'AWAITING_CONFIRMATION'
                      CHECK (status IN ('AWAITING_CONFIRMATION', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'DECLINED')),
    starts_at         timestamptz  NOT NULL,
    ends_at           timestamptz  NOT NULL,
    fulfilment        varchar(10)  NOT NULL CHECK (fulfilment IN ('AT_CLIENT', 'AT_STUDIO', 'PICKUP', 'DELIVERY')),
    address_id        uuid REFERENCES addresses(id) ON DELETE SET NULL,
    address_snapshot  jsonb,                                   -- copy at booking time, survives address edits
    note              varchar(500),
    service_name      varchar(80)  NOT NULL,                   -- snapshots so history stays correct if the service changes
    duration_minutes  int          NOT NULL,
    price_amount      bigint       NOT NULL CHECK (price_amount >= 0),
    currency          char(3)      NOT NULL DEFAULT 'INR',
    cancel_reason     varchar(500),
    decline_reason    varchar(500),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (fulfilment NOT IN ('AT_CLIENT', 'DELIVERY') OR address_snapshot IS NOT NULL OR address_id IS NOT NULL),
    -- A provider cannot have two live bookings that overlap in time (the "409 slot taken" case).
    CONSTRAINT no_overlapping_bookings EXCLUDE USING gist (
        business_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status IN ('AWAITING_CONFIRMATION', 'CONFIRMED'))
);
CREATE TRIGGER trg_bookings_updated BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX idx_bookings_customer ON bookings (customer_id, starts_at DESC);
CREATE INDEX idx_bookings_business ON bookings (business_id, status, starts_at);

CREATE TABLE cart_items (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id   uuid        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    starts_at    timestamptz,
    fulfilment   varchar(10) CHECK (fulfilment IN ('AT_CLIENT', 'AT_STUDIO', 'PICKUP', 'DELIVERY')),
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cart_user ON cart_items (user_id);

-- Stores the result of a request keyed by the Idempotency-Key header (POST /bookings, /cart/checkout).
CREATE TABLE idempotency_keys (
    user_id          uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key              uuid         NOT NULL,
    request_path     varchar(200) NOT NULL,
    response_status  int,
    response_body    jsonb,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

-- ---------- reviews -----------------------------------------------------------
CREATE TABLE reviews (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      uuid        NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,   -- one review per booking
    business_id     uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    author_id       uuid        NOT NULL REFERENCES users(id),
    rating          smallint    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    text            varchar(1000),
    provider_reply  varchar(1000),
    replied_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_reviews_business ON reviews (business_id, created_at DESC);

-- Keep businesses.rating_avg / review_count current so browse queries stay cheap.
CREATE FUNCTION refresh_business_rating() RETURNS trigger AS $$
DECLARE target uuid := COALESCE(NEW.business_id, OLD.business_id);
BEGIN
    UPDATE businesses b
       SET rating_avg   = COALESCE((SELECT round(avg(rating)::numeric, 2) FROM reviews WHERE business_id = target), 0),
           review_count = (SELECT count(*) FROM reviews WHERE business_id = target)
     WHERE b.id = target;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_reviews_rating AFTER INSERT OR UPDATE OF rating OR DELETE ON reviews
    FOR EACH ROW EXECUTE FUNCTION refresh_business_rating();

-- ---------- messages ----------------------------------------------------------
CREATE TABLE conversations (            -- one per customer and business pair
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id  uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    booking_id   uuid REFERENCES bookings(id) ON DELETE SET NULL,   -- booking shown beside the chat
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (customer_id, business_id)
);

CREATE TABLE messages (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  uuid        NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id        uuid        NOT NULL REFERENCES users(id),
    text             varchar(2000) NOT NULL,
    sent_at          timestamptz NOT NULL DEFAULT now(),
    read_at          timestamptz
);
CREATE INDEX idx_messages_thread ON messages (conversation_id, sent_at DESC);
CREATE INDEX idx_messages_unread ON messages (conversation_id) WHERE read_at IS NULL;

-- ---------- assistant ("Ask Niva") ------------------------------------------
CREATE TABLE assistant_threads (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE assistant_messages (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id   uuid        NOT NULL REFERENCES assistant_threads(id) ON DELETE CASCADE,
    role        varchar(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text        text        NOT NULL,
    parsed      jsonb,                          -- category, startsAt, maxBudget understood from the request
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_assistant_messages_thread ON assistant_messages (thread_id, created_at);

CREATE TABLE assistant_recommendations (   -- what POST /assistant/threads/{id}/book refers to
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id    uuid        NOT NULL REFERENCES assistant_threads(id) ON DELETE CASCADE,
    message_id   uuid REFERENCES assistant_messages(id) ON DELETE CASCADE,
    business_id  uuid        NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id   uuid        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL,
    reason       text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_assistant_recs_thread ON assistant_recommendations (thread_id);

-- ---------- notifications ----------------------------------------------------
CREATE TABLE notifications (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        varchar(25)  NOT NULL CHECK (type IN
                ('BOOKING_REQUESTED', 'BOOKING_CONFIRMED', 'BOOKING_DECLINED', 'BOOKING_CANCELLED', 'MESSAGE', 'REVIEW', 'SYSTEM')),
    title       varchar(160) NOT NULL,
    body        text,
    deep_link   varchar(200),
    read_at     timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;
