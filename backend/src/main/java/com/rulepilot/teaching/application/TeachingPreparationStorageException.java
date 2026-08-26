package com.rulepilot.teaching.application;

/** Marks a durable Teaching-evidence read or write failure that requires storage repair before retrying. */
final class TeachingPreparationStorageException extends RuntimeException {

    TeachingPreparationStorageException(Throwable cause) {
        super("Teaching preparation evidence storage is unavailable", cause);
    }
}
