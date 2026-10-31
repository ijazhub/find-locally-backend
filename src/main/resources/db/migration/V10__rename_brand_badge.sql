-- Preserve applied migration checksums; migrate the persisted brand identifier here.
ALTER TABLE business_badges DROP CONSTRAINT business_badges_badge_check;
ALTER TABLE business_badges ALTER COLUMN badge TYPE varchar(32);
UPDATE business_badges SET badge='TRUSTED_BY_FINDLOCALLY' WHERE badge='TRUSTED_BY_NIVA';
ALTER TABLE business_badges ADD CONSTRAINT business_badges_badge_check
 CHECK (badge IN ('VERIFIED','TOP_RATED','IDENTITY_CHECKED','TRUSTED_BY_FINDLOCALLY'));
