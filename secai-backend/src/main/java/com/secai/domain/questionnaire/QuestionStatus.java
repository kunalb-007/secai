package com.secai.domain.questionnaire;

public enum QuestionStatus {
    PENDING,        // not yet answered by AI
    GENERATED,      // AI has answered
    APPROVED,       // human approved AI answer
    EDITED,         // human edited the answer
    REJECTED        // human rejected AI answer; manual_answer is blank
}