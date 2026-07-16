-- GIN index for PostgreSQL full-text search on document chunks.
-- Enables keyword-based search for exact security terms (AES-256, TLS 1.3, SOC 2 Type II, etc.)
-- that semantic/vector search can miss due to embedding tokenization.
CREATE INDEX IF NOT EXISTS idx_document_chunk_text_fts
    ON document_chunk USING GIN (to_tsvector('english', text));

-- Source chunk linking on the question table.
-- Allows the review UI to show "View Source" — the exact paragraph the AI read.
-- Both columns are nullable: pre-existing questions and unanswerable questions have no source.
ALTER TABLE question
    ADD COLUMN IF NOT EXISTS source_chunk_id    UUID,
    ADD COLUMN IF NOT EXISTS source_document_id UUID;