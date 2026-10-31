-- Preflight intentionally refuses to guess how historical dual-role accounts should migrate.
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM users u LEFT JOIN user_roles r ON r.user_id=u.id
            GROUP BY u.id HAVING count(r.role) <> 1)
 OR EXISTS (SELECT 1 FROM businesses b WHERE NOT EXISTS
            (SELECT 1 FROM user_roles r WHERE r.user_id=b.owner_user_id AND r.role='BUSINESS')) THEN
   RAISE EXCEPTION 'Account type audit required: run docs/account-type-audit.sql and approve remediation before retrying';
 END IF;
END $$;
ALTER TABLE user_roles ADD CONSTRAINT one_account_type UNIQUE (user_id);
CREATE FUNCTION enforce_account_type() RETURNS trigger AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM users WHERE id=COALESCE(NEW.user_id,OLD.user_id))
 AND (SELECT count(*) FROM user_roles WHERE user_id=COALESCE(NEW.user_id,OLD.user_id)) <> 1 THEN
   RAISE EXCEPTION 'Exactly one account type is required' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER account_type_required AFTER INSERT OR UPDATE OR DELETE ON user_roles
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_account_type();
CREATE FUNCTION prevent_account_switch() RETURNS trigger AS $$ BEGIN
 RAISE EXCEPTION 'Account type is immutable' USING ERRCODE='23514';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER account_type_immutable BEFORE UPDATE ON user_roles FOR EACH ROW EXECUTE FUNCTION prevent_account_switch();
CREATE FUNCTION prevent_account_type_delete() RETURNS trigger AS $$ BEGIN
 IF EXISTS(SELECT 1 FROM users WHERE id=OLD.user_id) THEN
  RAISE EXCEPTION 'Account type is immutable' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER account_type_delete_guard BEFORE DELETE ON user_roles FOR EACH ROW EXECUTE FUNCTION prevent_account_type_delete();
CREATE FUNCTION require_new_account_type() RETURNS trigger AS $$ BEGIN
 IF EXISTS(SELECT 1 FROM users WHERE id=NEW.id) AND NOT EXISTS(SELECT 1 FROM user_roles WHERE user_id=NEW.id) THEN
  RAISE EXCEPTION 'Account type is required' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER new_account_type_required AFTER INSERT ON users DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION require_new_account_type();

ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ALTER COLUMN status TYPE varchar(40);
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check CHECK (status IN
 ('AWAITING_CONFIRMATION','AWAITING_CUSTOMER_APPROVAL','CONFIRMED','DECLINED','CANCELLED','EXPIRED','COMPLETED'));
ALTER TABLE bookings ADD COLUMN proposed_amount bigint CHECK (proposed_amount >= 0);
ALTER TABLE bookings ADD COLUMN agreed_amount bigint CHECK (agreed_amount >= 0);
ALTER TABLE bookings ADD COLUMN hold_expires_at timestamptz;
ALTER TABLE bookings ADD COLUMN response_deadline timestamptz;
UPDATE bookings SET proposed_amount=price_amount,
 agreed_amount=CASE WHEN status IN ('CONFIRMED','COMPLETED') THEN price_amount END,
 hold_expires_at=least(starts_at,created_at+interval '24 hours'),
 response_deadline=least(starts_at,created_at+interval '24 hours');
ALTER TABLE bookings ALTER COLUMN proposed_amount SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN hold_expires_at SET NOT NULL;
ALTER TABLE bookings ALTER COLUMN response_deadline SET NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT bounded_booking_deadline CHECK(response_deadline <= hold_expires_at AND hold_expires_at <= starts_at);
ALTER TABLE bookings DROP CONSTRAINT no_overlapping_bookings;
ALTER TABLE bookings ADD CONSTRAINT no_overlapping_bookings EXCLUDE USING gist
 (business_id WITH =, tstzrange(starts_at,ends_at) WITH &&)
 WHERE(status IN ('AWAITING_CONFIRMATION','AWAITING_CUSTOMER_APPROVAL','CONFIRMED'));
CREATE INDEX booking_expiry_due ON bookings(response_deadline)
 WHERE status IN ('AWAITING_CONFIRMATION','AWAITING_CUSTOMER_APPROVAL');

CREATE TABLE booking_quotations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), booking_id uuid NOT NULL REFERENCES bookings(id),
 version integer NOT NULL CHECK(version>0), amount bigint NOT NULL CHECK(amount>=0), currency char(3) NOT NULL,
 state varchar(15) NOT NULL CHECK(state IN ('CURRENT','SUPERSEDED','ACCEPTED','REJECTED','EXPIRED')),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 UNIQUE(booking_id,version), CHECK(expires_at>created_at)
);
CREATE UNIQUE INDEX one_current_quotation ON booking_quotations(booking_id) WHERE state='CURRENT';
CREATE TABLE booking_transitions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), booking_id uuid NOT NULL REFERENCES bookings(id),
 from_status varchar(40), to_status varchar(40) NOT NULL, actor_id uuid REFERENCES users(id),
 reason varchar(500), quotation_id uuid REFERENCES booking_quotations(id), occurred_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO booking_transitions(booking_id,to_status,reason)
 SELECT id,status,'Legacy state at migration; earlier transitions unavailable' FROM bookings;
ALTER TABLE outbox_events DROP CONSTRAINT outbox_events_aggregate_id_event_type_key;
CREATE UNIQUE INDEX one_business_onboarded_event ON outbox_events(aggregate_id,event_type) WHERE event_type='BUSINESS_ONBOARDED';
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ALTER COLUMN type TYPE varchar(40);
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK(type IN
 ('BOOKING_REQUESTED','BOOKING_CONFIRMED','BOOKING_DECLINED','BOOKING_CANCELLED','MESSAGE','REVIEW','SYSTEM',
 'QUOTATION_ISSUED','QUOTATION_ACCEPTED','QUOTATION_REJECTED','BOOKING_EXPIRED'));

CREATE FUNCTION guard_marketplace_identity() RETURNS trigger AS $$
DECLARE target uuid; expected text;
BEGIN
 IF TG_TABLE_NAME='businesses' THEN target:=NEW.owner_user_id; expected:='BUSINESS';
 ELSE target:=NEW.customer_id; expected:='CUSTOMER'; END IF;
 IF NOT EXISTS (SELECT 1 FROM user_roles WHERE user_id=target AND role=expected) THEN
  RAISE EXCEPTION 'Account type is not authorized for this operation' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER business_account_guard BEFORE INSERT ON businesses FOR EACH ROW EXECUTE FUNCTION guard_marketplace_identity();
CREATE TRIGGER booking_account_guard BEFORE INSERT ON bookings FOR EACH ROW EXECUTE FUNCTION guard_marketplace_identity();

CREATE FUNCTION audit_booking_transition() RETURNS trigger AS $$
DECLARE actor uuid; event_id uuid:=gen_random_uuid(); kind text; quote uuid;
BEGIN
 actor:=NULLIF(current_setting('app.actor_id',true),'')::uuid;
 IF TG_OP='UPDATE' AND NEW.status=OLD.status THEN RETURN NEW; END IF;
 SELECT id INTO quote FROM booking_quotations WHERE booking_id=NEW.id ORDER BY version DESC LIMIT 1;
 INSERT INTO booking_transitions(booking_id,from_status,to_status,actor_id,reason,quotation_id)
 VALUES(NEW.id,CASE WHEN TG_OP='UPDATE' THEN OLD.status END,NEW.status,actor,
        COALESCE(NEW.decline_reason,NEW.cancel_reason),quote);
 IF NEW.status IN ('CANCELLED','DECLINED','EXPIRED') THEN
  UPDATE booking_quotations SET state=CASE WHEN NEW.status='EXPIRED' THEN 'EXPIRED' ELSE 'REJECTED' END
   WHERE booking_id=NEW.id AND state='CURRENT';
 END IF;
 kind:=CASE WHEN NEW.status='EXPIRED' THEN 'BOOKING_EXPIRED'
            WHEN NEW.status='CONFIRMED' AND TG_OP='UPDATE' AND OLD.status='AWAITING_CUSTOMER_APPROVAL' THEN 'QUOTATION_ACCEPTED'
            WHEN NEW.status='DECLINED' AND TG_OP='UPDATE' AND OLD.status='AWAITING_CUSTOMER_APPROVAL' THEN 'QUOTATION_REJECTED'
            ELSE 'BOOKING_TRANSITIONED' END;
 INSERT INTO outbox_events(id,aggregate_id,event_type,payload)
 VALUES(event_id,NEW.id,kind,jsonb_build_object('schemaVersion',1,'eventId',event_id,'eventType',kind,
    'occurredAt',now(),'bookingId',NEW.id,'status',NEW.status,'quotationId',quote)::text);
 IF kind IN ('BOOKING_EXPIRED','QUOTATION_ACCEPTED','QUOTATION_REJECTED') THEN
  INSERT INTO notifications(user_id,type,title,deep_link)
  SELECT recipient,kind,replace(kind,'_',' '),'/bookings/'||NEW.id FROM
    (SELECT NEW.customer_id AS recipient UNION SELECT owner_user_id FROM businesses WHERE id=NEW.business_id) recipients;
 END IF;
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER booking_transition_audit AFTER INSERT OR UPDATE OF status ON bookings
 FOR EACH ROW EXECUTE FUNCTION audit_booking_transition();

CREATE FUNCTION audit_quotation_issuance() RETURNS trigger AS $$
DECLARE event_id uuid:=gen_random_uuid();
BEGIN
 INSERT INTO booking_transitions(booking_id,from_status,to_status,actor_id,quotation_id,reason)
 SELECT NEW.booking_id,status,'AWAITING_CUSTOMER_APPROVAL',NULLIF(current_setting('app.actor_id',true),'')::uuid,NEW.id,'Quotation issued'
 FROM bookings WHERE id=NEW.booking_id;
 INSERT INTO notifications(user_id,type,title,deep_link)
 SELECT customer_id,'QUOTATION_ISSUED','New quotation','/bookings/'||NEW.booking_id FROM bookings WHERE id=NEW.booking_id;
 INSERT INTO outbox_events(id,aggregate_id,event_type,payload)
 VALUES(event_id,NEW.booking_id,'QUOTATION_ISSUED',jsonb_build_object('schemaVersion',1,'eventId',event_id,
   'eventType','QUOTATION_ISSUED','occurredAt',now(),'bookingId',NEW.booking_id,'quotationId',NEW.id,'version',NEW.version)::text);
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER quotation_issued_audit AFTER INSERT ON booking_quotations FOR EACH ROW EXECUTE FUNCTION audit_quotation_issuance();
