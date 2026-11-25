CREATE TABLE user_photos (
    user_id UUID PRIMARY KEY REFERENCES user_profiles(id) ON DELETE CASCADE,
    data BYTEA NOT NULL CHECK (octet_length(data) > 0),
    format VARCHAR(10) NOT NULL CHECK (format IN ('JPEG', 'PNG')),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
