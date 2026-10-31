ALTER TABLE uploads ADD COLUMN processing_state varchar(10) NOT NULL DEFAULT 'PENDING'
 CHECK(processing_state IN ('PENDING','READY','FAILED'));
ALTER TABLE uploads ADD COLUMN width integer CHECK(width>0);
ALTER TABLE uploads ADD COLUMN height integer CHECK(height>0);
ALTER TABLE uploads ADD COLUMN duration_seconds integer CHECK(duration_seconds>=0);
ALTER TABLE uploads ADD CONSTRAINT ready_video_metadata CHECK(processing_state<>'READY' OR purpose<>'GALLERY_VIDEO'
 OR (width IS NOT NULL AND height IS NOT NULL AND duration_seconds IS NOT NULL AND duration_seconds>0));
-- Legacy transfer completion is not evidence that video was inspected. Preserve rows but quarantine playback.
UPDATE uploads SET processing_state='READY' WHERE status='UPLOADED' AND content_type LIKE 'image/%';
ALTER TABLE business_media ADD COLUMN presentation_format varchar(10) NOT NULL DEFAULT 'STANDARD'
 CHECK(presentation_format IN ('STANDARD','REEL'));
ALTER TABLE business_media ADD COLUMN processing_state varchar(10) NOT NULL DEFAULT 'PENDING'
 CHECK(processing_state IN ('PENDING','READY','FAILED'));
ALTER TABLE business_media ADD COLUMN publication_state varchar(10) NOT NULL DEFAULT 'DRAFT'
 CHECK(publication_state IN ('DRAFT','PUBLISHED'));
ALTER TABLE business_media ADD COLUMN width integer CHECK(width>0);
ALTER TABLE business_media ADD COLUMN height integer CHECK(height>0);
ALTER TABLE business_media ADD COLUMN cover_upload_id uuid REFERENCES uploads(id);
UPDATE business_media m SET processing_state='READY',publication_state='PUBLISHED'
 FROM uploads u,businesses b WHERE m.type='PHOTO' AND b.id=m.business_id AND u.id=m.upload_id
 AND u.user_id=b.owner_user_id AND u.purpose='GALLERY_PHOTO' AND u.status='UPLOADED' AND u.processing_state='READY';
ALTER TABLE business_media ADD CONSTRAINT reel_requires_video CHECK(presentation_format<>'REEL' OR type='VIDEO');
ALTER TABLE business_media ADD CONSTRAINT publish_ready_only CHECK(publication_state<>'PUBLISHED' OR processing_state='READY');

CREATE FUNCTION validate_gallery_media() RETURNS trigger AS $$
DECLARE file uploads%ROWTYPE; owner_id uuid; cover uploads%ROWTYPE;
BEGIN
 SELECT owner_user_id INTO owner_id FROM businesses WHERE id=NEW.business_id;
 SELECT * INTO file FROM uploads WHERE id=NEW.upload_id AND user_id=owner_id AND status='UPLOADED';
 IF NOT FOUND OR (NEW.type='VIDEO' AND file.purpose<>'GALLERY_VIDEO')
              OR (NEW.type='PHOTO' AND file.purpose<>'GALLERY_PHOTO') THEN
  RAISE EXCEPTION 'Owned completed gallery upload required' USING ERRCODE='23514';
 END IF;
 NEW.processing_state:=file.processing_state; NEW.width:=file.width; NEW.height:=file.height;
 NEW.duration_seconds:=file.duration_seconds;
 IF TG_OP='INSERT' THEN
  NEW.publication_state:=CASE WHEN file.processing_state='READY' THEN 'PUBLISHED' ELSE 'DRAFT' END;
 END IF;
 IF NEW.cover_upload_id IS NOT NULL THEN
  SELECT * INTO cover FROM uploads WHERE id=NEW.cover_upload_id AND user_id=owner_id
   AND purpose IN ('COVER','GALLERY_PHOTO') AND status='UPLOADED' AND processing_state='READY';
  IF NOT FOUND THEN RAISE EXCEPTION 'Owned ready cover image required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER gallery_upload_guard BEFORE INSERT OR UPDATE ON business_media FOR EACH ROW EXECUTE FUNCTION validate_gallery_media();
CREATE FUNCTION immutable_upload_identity() RETURNS trigger AS $$ BEGIN
 IF NEW.user_id<>OLD.user_id OR NEW.purpose<>OLD.purpose OR NEW.object_key<>OLD.object_key
    OR NEW.content_type<>OLD.content_type OR NEW.size_bytes<>OLD.size_bytes THEN
  RAISE EXCEPTION 'Upload ownership and file identity are immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER upload_identity_guard BEFORE UPDATE ON uploads FOR EACH ROW EXECUTE FUNCTION immutable_upload_identity();
