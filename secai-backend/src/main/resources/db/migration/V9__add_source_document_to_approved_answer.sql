ALTER TABLE approved_answer
    ADD COLUMN IF NOT EXISTS source_document_id UUID;