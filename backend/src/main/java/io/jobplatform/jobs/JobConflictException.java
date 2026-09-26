package io.jobplatform.jobs;

public class JobConflictException extends RuntimeException {
    private final String code;
    public JobConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
