package com.secai.parsing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rich parsing diagnostics attached to every ParseResult.
 * Surfaces enough information to debug any questionnaire format.
 */
public record ParseDiagnostics(
        QuestionnaireFormat formatDetected,
        List<String>        sheetsProcessed,
        List<String>        sheetsSkipped,
        int                 candidateRows,
        int                 extractedRows,
        int                 skippedRows,
        List<String>        warnings
) {
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private QuestionnaireFormat    formatDetected = QuestionnaireFormat.GENERIC;
        private final List<String>     sheetsProcessed = new ArrayList<>();
        private final List<String>     sheetsSkipped   = new ArrayList<>();
        private int                    candidateRows   = 0;
        private int                    extractedRows   = 0;
        private int                    skippedRows     = 0;
        private final List<String>     warnings        = new ArrayList<>();

        public Builder format(QuestionnaireFormat f)    { this.formatDetected = f; return this; }
        public Builder processed(String sheet)          { sheetsProcessed.add(sheet); return this; }
        public Builder skipped(String sheet, String why){ sheetsSkipped.add(sheet + " (" + why + ")"); return this; }
        public Builder candidates(int n)                { this.candidateRows += n; return this; }
        public Builder extracted(int n)                 { this.extractedRows += n; return this; }
        public Builder skippedRow()                     { this.skippedRows++; return this; }
        public Builder warn(String msg)                 { warnings.add(msg); return this; }

        public ParseDiagnostics build() {
            return new ParseDiagnostics(
                    formatDetected,
                    Collections.unmodifiableList(sheetsProcessed),
                    Collections.unmodifiableList(sheetsSkipped),
                    candidateRows,
                    extractedRows,
                    skippedRows,
                    Collections.unmodifiableList(warnings)
            );
        }
    }
}