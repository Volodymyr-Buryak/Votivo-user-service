CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    email VARCHAR(320),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    email_verified BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES user_profiles(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'admin')),
    PRIMARY KEY (user_id, role)
);

