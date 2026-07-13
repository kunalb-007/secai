CREATE TABLE document (
                          id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          organization_id UUID        NOT NULL REFERENCES organization(id),
                          filename        VARCHAR(255) NOT NULL,
                          content_type    VARCHAR(100),
                          status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                          storage_path    VARCHAR(512),
                          uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          processed_at    TIMESTAMPTZ
);

CREATE INDEX idx_document_org    ON document(organization_id);
CREATE INDEX idx_document_status ON document(status);