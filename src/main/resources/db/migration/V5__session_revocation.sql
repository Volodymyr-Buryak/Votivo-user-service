ALTER TABLE user_profiles ADD COLUMN sessions_revoked_at TIMESTAMP WITH TIME ZONE;
UPDATE user_profiles SET sessions_revoked_at = CURRENT_TIMESTAMP WHERE enabled = false;
