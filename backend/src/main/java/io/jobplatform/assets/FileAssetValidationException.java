package io.jobplatform.assets;

import io.jobplatform.jobs.JobValidationException;

/** Returns the platform's normal, correlation-aware 400 response for invalid file input. */
public class FileAssetValidationException extends JobValidationException {
    public FileAssetValidationException(String message) {
        super(message);
    }
}
