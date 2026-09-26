package io.jobplatform.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signed cursors prevent callers from supplying arbitrary pagination positions and expire after 15 minutes. */
@Component
class JobCursorCodec {
    private static final Duration TTL = Duration.ofMinutes(15);
    private final byte[] key;

    JobCursorCodec(@Value("${app.security.jwt-secret}") String secret) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    String encode(Instant createdAt, UUID id) {
        String payload = Instant.now().getEpochSecond() + "|" + createdAt.toEpochMilli() + "|" + id;
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
    }

    Cursor decode(String value) {
        try {
            String[] pieces = value.split("\\.");
            if (pieces.length != 2 || !MessageDigest.isEqual(sign(pieces[0]), Base64.getUrlDecoder().decode(pieces[1]))) {
                throw new IllegalArgumentException();
            }
            String[] fields = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8).split("\\|");
            if (fields.length != 3) throw new IllegalArgumentException();
            Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(fields[0]));
            if (issuedAt.plus(TTL).isBefore(Instant.now())) throw new IllegalArgumentException();
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(fields[1])), UUID.fromString(fields[2]));
        } catch (RuntimeException exception) {
            throw new JobValidationException("cursor is invalid or has expired.");
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign cursor.", exception);
        }
    }

    record Cursor(Instant createdAt, UUID id) { }
}
