-- Optional performance index for the review page status-filter queries.
-- Covers: findByQuestionnaireIdAndOrganizationIdAndStatus (used by filter dropdown).
CREATE INDEX IF NOT EXISTS idx_question_questionnaire_org_status
    ON question(questionnaire_id, organization_id, status);