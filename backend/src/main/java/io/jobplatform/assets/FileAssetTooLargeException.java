package io.jobplatform.assets;

/** Kept distinct from ordinary validation so both servlet and service limits return HTTP 413. */
public class FileAssetTooLargeException extends RuntimeException {
    public FileAssetTooLargeException(String message) {
        super(message);
    }
}
