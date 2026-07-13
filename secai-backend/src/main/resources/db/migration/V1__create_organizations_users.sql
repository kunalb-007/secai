CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE organization (
                              id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                              name        VARCHAR(255) NOT NULL,
                              created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
                          id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          email           VARCHAR(255) NOT NULL UNIQUE,
                          password_hash   VARCHAR(255) NOT NULL,
                          organization_id UUID        NOT NULL REFERENCES organization(id),
                          created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_user_email ON app_user(email);
CREATE INDEX idx_app_user_org   ON app_user(organization_id);