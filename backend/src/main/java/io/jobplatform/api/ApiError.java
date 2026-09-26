package io.jobplatform.api;

public record ApiError(String code, String message, String correlationId) { }
