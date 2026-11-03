package co.com.finova.audit.api.dto;

import co.com.finova.audit.model.verification.ChainVerification;

/** Verification result (spec §3.11): valid, checkedRecords, firstInvalidSeq, verifiedAt. */
public record ChainVerificationResponse(boolean valid, long checkedRecords, Long firstInvalidSeq,
                                        String verifiedAt) {

    public static ChainVerificationResponse from(ChainVerification verification) {
        return new ChainVerificationResponse(verification.valid(), verification.checkedRecords(),
                verification.firstInvalidSeq(), verification.verifiedAt().toString());
    }
}
