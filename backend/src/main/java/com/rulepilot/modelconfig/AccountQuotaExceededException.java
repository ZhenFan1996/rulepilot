package com.rulepilot.modelconfig;

public final class AccountQuotaExceededException extends RuntimeException {

    public AccountQuotaExceededException() {
        super("ACCOUNT_QUOTA_EXHAUSTED");
    }
}
