-- Stores the result of coverage analysis for a questionnaire.
-- One row per questionnaire, one JSON blob for all category breakdowns.
-- Re-analysis overwrites the existing row.

CREATE TABLE coverage_report (
                                 id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                 questionnaire_id    UUID        NOT NULL REFERENCES questionnaire(id) ON DELETE CASCADE,
                                 organization_id     UUID        NOT NULL REFERENCES organization(id),

    -- Overall stats
                                 overall_coverage    DECIMAL(5,4) NOT NULL,         -- 0.0000–1.0000
                                 total_questions     INT          NOT NULL,
                                 answerable_questions INT         NOT NULL,

    -- Per-category breakdown stored as JSONB for flexibility
    -- Structure: [{ "category": "Access Control", "coverage": 0.94,
    --               "totalQuestions": 40, "answerableQuestions": 38,
    --               "missingDocSuggestion": null }]
                                 category_breakdown  JSONB        NOT NULL DEFAULT '[]',

    -- Suggested missing documents derived from low-coverage categories
    -- Structure: ["Business Continuity Plan", "Vendor Risk Management Policy"]
                                 missing_doc_suggestions JSONB   NOT NULL DEFAULT '[]',

    -- Estimated coverage if suggested docs were uploaded
                                 estimated_coverage_after DECIMAL(5,4),

                                 status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    -- PENDING | RUNNING | COMPLETE | FAILED

                                 started_at          TIMESTAMPTZ,
                                 completed_at        TIMESTAMPTZ,
                                 created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_coverage_questionnaire
    ON coverage_report(questionnaire_id);

CREATE INDEX idx_coverage_org
    ON coverage_report(organization_id);