-- ── Questionnaire ────────────────────────────────────────────────────────────
CREATE TABLE questionnaire (
                               id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                               organization_id     UUID        NOT NULL REFERENCES organization(id),
                               filename            VARCHAR(255) NOT NULL,
                               original_format     VARCHAR(20) NOT NULL,          -- XLSX, CSV, DOCX
                               status              VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
                               total_questions     INT         NOT NULL DEFAULT 0,
                               parse_confidence    DOUBLE PRECISION,                  -- 0.000 to 1.000
                               low_confidence_flag BOOLEAN     NOT NULL DEFAULT FALSE,
                               uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_questionnaire_org ON questionnaire(organization_id);

-- ── Question ─────────────────────────────────────────────────────────────────
CREATE TABLE question (
                          id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          questionnaire_id    UUID        NOT NULL REFERENCES questionnaire(id) ON DELETE CASCADE,
                          organization_id     UUID        NOT NULL REFERENCES organization(id),  -- for fast tenant scoping
                          question_number     VARCHAR(50),                   -- "1.1", "Q42", "3"
                          question_text       TEXT        NOT NULL,
                          category            VARCHAR(255),                  -- sheet name or section header
                          ai_answer           TEXT,
                          evidence            VARCHAR(500),
                          retrieval_score     DOUBLE PRECISION,
                          status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                          manual_answer       TEXT,
                          sort_order          INT         NOT NULL DEFAULT 0, -- preserves original row order
                          created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_question_questionnaire ON question(questionnaire_id);
CREATE INDEX idx_question_org           ON question(organization_id);
CREATE INDEX idx_question_status        ON question(questionnaire_id, status);

-- ── AI Generation Job ────────────────────────────────────────────────────────
CREATE TABLE ai_generation_job (
                                   id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                   questionnaire_id    UUID        NOT NULL REFERENCES questionnaire(id) ON DELETE CASCADE,
                                   status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                                   total_questions     INT         NOT NULL DEFAULT 0,
                                   completed_questions INT         NOT NULL DEFAULT 0,
                                   started_at          TIMESTAMPTZ,
                                   finished_at         TIMESTAMPTZ,
                                   created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_questionnaire ON ai_generation_job(questionnaire_id);