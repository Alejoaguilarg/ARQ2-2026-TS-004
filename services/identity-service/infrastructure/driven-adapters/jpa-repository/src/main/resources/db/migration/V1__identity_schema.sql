-- spec.md §2 Identity (identity_db). Secrets are stored only as argon2 hashes (passwords, PINs)
-- or SHA-256 (refresh tokens); the OTP secret is stored encrypted.

CREATE TABLE users (
    user_id       UUID PRIMARY KEY,
    -- Stored lower-cased so lookups and the UNIQUE constraint ignore case.
    email         VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    phone         VARCHAR(16),
    status        VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    roles         TEXT[]       NOT NULL CHECK (roles <@ ARRAY['USER', 'AUDITOR']::TEXT[]),
    password_hash TEXT         NOT NULL,
    pin_hash      TEXT         NOT NULL,
    otp_secret    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    user_id       UUID         NOT NULL REFERENCES users (user_id),
    device_id     VARCHAR(128) NOT NULL,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_id)
);

-- One family per login on one device; family_id is the sid of its access tokens.
CREATE TABLE refresh_tokens (
    token_hash CHAR(64)     PRIMARY KEY,
    family_id  UUID         NOT NULL,
    user_id    UUID         NOT NULL REFERENCES users (user_id),
    device_id  VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
