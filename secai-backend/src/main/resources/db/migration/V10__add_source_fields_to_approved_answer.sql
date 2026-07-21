-- --------------------------------------------------------------------------
-- APPROVED ANSWER
-- --------------------------------------------------------------------------

ALTER TABLE approved_answer
    ADD COLUMN IF NOT EXISTS source_chunk_id UUID;

ALTER TABLE approved_answer
    ADD COLUMN IF NOT EXISTS source_document_id UUID;

-- Optional indexes (recommended for lookups)

CREATE INDEX IF NOT EXISTS idx_approved_answer_source_chunk
    ON approved_answer(source_chunk_id);

CREATE INDEX IF NOT EXISTS idx_approved_answer_source_document
    ON approved_answer(source_document_id);
