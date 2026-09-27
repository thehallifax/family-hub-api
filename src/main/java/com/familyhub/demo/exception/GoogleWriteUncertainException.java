package com.familyhub.demo.exception;

/** Google accepted the write, but FamilyHub could not confirm its local import. */
public class GoogleWriteUncertainException extends RuntimeException {
    public GoogleWriteUncertainException(Throwable cause) {
        super("Google created the event, but FamilyHub could not confirm its local copy. Use Sync Now before trying again.", cause);
    }

    public GoogleWriteUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
