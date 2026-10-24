CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    payload text NOT NULL CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    status varchar(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RETRY', 'SENT', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    sqs_message_id text,
    last_error varchar(200),
    UNIQUE (aggregate_id, event_type)
);
CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRY');
