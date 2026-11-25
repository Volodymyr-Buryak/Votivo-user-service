ALTER TABLE user_profiles ADD COLUMN identity_deletion_pending BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_user_profiles_identity_deletion_pending ON user_profiles(identity_deletion_pending);
