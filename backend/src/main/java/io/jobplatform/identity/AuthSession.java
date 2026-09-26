package io.jobplatform.identity;

public record AuthSession(String accessToken, String refreshToken, long expiresIn) { }
