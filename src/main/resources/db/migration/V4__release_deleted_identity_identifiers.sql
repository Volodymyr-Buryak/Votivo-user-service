-- Reprocess tombstones completed before email/username anonymization was introduced.
-- Disabled (blocked) accounts without deleted_at must retain their identifiers.
UPDATE user_profiles
SET identity_deletion_pending = TRUE
WHERE deleted_at IS NOT NULL;
