package com.rulepilot.document.application;

public final class OfficialRulebookImportIdentityException extends RuntimeException {

    private final Code code;
    private final OfficialRulebookImportIdentity.Review review;

    private OfficialRulebookImportIdentityException(
            Code code, String message, OfficialRulebookImportIdentity.Review review) {
        super(message);
        this.code = code;
        this.review = review;
    }

    public static OfficialRulebookImportIdentityException confirmationRequired(
            OfficialRulebookImportIdentity.Review review) {
        return new OfficialRulebookImportIdentityException(
                Code.CONFIRMATION_REQUIRED,
                "Rulebook source identity must be confirmed before import.",
                review);
    }

    public static OfficialRulebookImportIdentityException activeImportConflict(
            OfficialRulebookImportIdentity.Review review) {
        return new OfficialRulebookImportIdentityException(
                Code.ACTIVE_IMPORT_CONFLICT,
                "This source is already being imported for another edition.",
                review);
    }

    public Code code() {
        return code;
    }

    public OfficialRulebookImportIdentity.Review review() {
        return review;
    }

    public enum Code {
        CONFIRMATION_REQUIRED,
        ACTIVE_IMPORT_CONFLICT
    }
}
