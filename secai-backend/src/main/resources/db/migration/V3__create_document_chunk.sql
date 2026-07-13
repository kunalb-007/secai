-- Enable pgvector extension (idempotent)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunk (
                                id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                organization_id UUID        NOT NULL REFERENCES organization(id),
                                document_id     UUID        NOT NULL REFERENCES document(id) ON DELETE CASCADE,
                                section_title   VARCHAR(500),
                                text            TEXT        NOT NULL,
                                embedding       VECTOR(1536),             -- text-embedding-3-small output
                                chunk_index     INT         NOT NULL,
                                token_count     INT,
                                created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for vector similarity search, scoped per org
-- Using IVFFlat index — adequate for MVP scale (< 1M chunks)
-- CREATE INDEX AFTER initial data load for performance
CREATE INDEX idx_chunk_org_id   ON document_chunk(organization_id);
CREATE INDEX idx_chunk_doc_id   ON document_chunk(document_id);

-- Vector index (creates after first real data load, or uncomment now)
-- CREATE INDEX idx_chunk_embedding ON document_chunk
--     USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Add retry_count column to document table (for Phase 3 retry logic)
ALTER TABLE document ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE document ADD COLUMN IF NOT EXISTS error_message TEXT;