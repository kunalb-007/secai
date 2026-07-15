-- Approved Answer Library
-- Stores every approved/edited answer as a searchable vector entry.
-- Scoped per organization — Org A never sees Org B's library.

CREATE TABLE approved_answer (
                                 id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                 organization_id     UUID        NOT NULL REFERENCES organization(id),

    -- Source question (for display in the "Found approved answer" card)
                                 source_question_id  UUID        REFERENCES question(id) ON DELETE SET NULL,
                                 source_question_text TEXT       NOT NULL,

    -- The canonical answer stored in the library
                                 answer_text         TEXT        NOT NULL,
                                 evidence            VARCHAR(500),

    -- Who approved it and when (for display)
                                 approved_by_email   VARCHAR(255),
                                 approved_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Embedding of source_question_text (used for similarity search)
                                 question_embedding  VECTOR(1536),

                                 created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_library_org ON approved_answer(organization_id);

-- Vector index for similarity search within an org's library
-- Using IVFFlat; for MVP scale (< 10k entries) exact search is also fine
-- Uncomment after first data load:
-- CREATE INDEX idx_library_embedding
--     ON approved_answer USING ivfflat (question_embedding vector_cosine_ops)
--     WITH (lists = 50);