package io.jobplatform.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final Duration accessTokenTtl;

    public JwtTokenService(ObjectMapper objectMapper, @Value("${app.security.jwt-secret}") String secret,
                           @Value("${app.security.access-token-ttl}") Duration accessTokenTtl) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("JWT signing key must be at least 32 bytes.");
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtl = accessTokenTtl;
    }

    public String create(ProductPrincipal principal) {
        try {
            String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = encode(objectMapper.writeValueAsString(Map.of(
                    "sub", principal.userId().toString(), "email", principal.email(), "role", principal.role(),
                    "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plus(accessTokenTtl).getEpochSecond())));
            String unsigned = header + "." + payload;
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(unsigned));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create access token.", exception);
        }
    }

    public ProductPrincipal parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidTokenException();
            }
            Map<String, Object> payload = objectMapper.readValue(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), new TypeReference<>() { });
            if (((Number) payload.get("exp")).longValue() <= Instant.now().getEpochSecond()) throw new InvalidTokenException();
            return new ProductPrincipal(UUID.fromString((String) payload.get("sub")), (String) payload.get("email"), (String) payload.get("role"));
        } catch (Exception exception) {
            if (exception instanceof InvalidTokenException invalid) throw invalid;
            throw new InvalidTokenException();
        }
    }

    private byte[] hmac(String data) throws InvalidKeyException, java.security.NoSuchAlgorithmException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }

    public static class InvalidTokenException extends RuntimeException { }
}
