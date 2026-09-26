package io.jobplatform.jobs;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String message) { super(message); }
}
