package io.jobplatform.identity;

import io.jobplatform.jobs.JobConflictException;
import io.jobplatform.security.JwtTokenService;
import io.jobplatform.security.ProductPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtTokenService jwt;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();
    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtTokenService jwt,
                           @Value("${app.security.refresh-token-ttl}") Duration refreshTtl) {
        this.jdbc = jdbc; this.passwords = passwords; this.jwt = jwt; this.refreshTtl = refreshTtl;
    }
    @Transactional
    public AuthSession register(RegisterRequest request) {
        UUID userId = UUID.randomUUID(); UUID projectId = UUID.randomUUID(); Instant now = Instant.now();
        try {
            jdbc.update("insert into users (id,email,password_hash,role,created_at) values (?,?,?,?,?)", userId, request.email().toLowerCase(), passwords.encode(request.password()), "USER", Timestamp.from(now));
            jdbc.update("insert into projects (id,owner_id,name,status,created_at) values (?,?,?,?,?)", projectId, userId, request.projectName(), "ACTIVE", Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) { throw new JobConflictException("EMAIL_ALREADY_REGISTERED", "An account with this email already exists."); }
        return createSession(new ProductPrincipal(userId, request.email().toLowerCase(), "USER"), null);
    }
    @Transactional
    public AuthSession login(AuthRequest request) {
        User user = jdbc.query("select id,email,password_hash,role from users where email = ?", rs -> rs.next() ? new User(rs.getObject(1, UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)) : null, request.email().toLowerCase());
        if (user == null || !passwords.matches(request.password(), user.passwordHash())) throw new InvalidCredentialsException();
        return createSession(new ProductPrincipal(user.id(), user.email(), user.role()), null);
    }
    @Transactional
    public AuthSession refresh(RefreshRequest request) {
        String hash = hash(request.refreshToken());
        Token token = jdbc.query("select id,user_id,family_id,expires_at,revoked_at from refresh_tokens where token_hash=? for update", rs -> rs.next() ? new Token(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()) : null, hash);
        if (token == null || token.revokedAt() != null || token.expiresAt().isBefore(Instant.now())) { if (token != null) jdbc.update("update refresh_tokens set revoked_at=current_timestamp where family_id=? and revoked_at is null", token.familyId()); throw new InvalidCredentialsException(); }
        jdbc.update("update refresh_tokens set revoked_at=current_timestamp,last_used_at=current_timestamp where id=?", token.id());
        User user = jdbc.query("select id,email,password_hash,role from users where id=?", rs -> rs.next() ? new User(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)) : null, token.userId());
        return createSession(new ProductPrincipal(user.id(), user.email(), user.role()), token.familyId());
    }
    private AuthSession createSession(ProductPrincipal principal, UUID familyId) {
        String raw = rawToken(); UUID tokenId = UUID.randomUUID(); UUID family = familyId == null ? UUID.randomUUID() : familyId; Instant now=Instant.now();
        jdbc.update("insert into refresh_tokens (id,user_id,family_id,token_hash,issued_at,expires_at) values (?,?,?,?,?,?)", tokenId,principal.userId(),family,hash(raw),Timestamp.from(now),Timestamp.from(now.plus(refreshTtl)));
        return new AuthSession(jwt.create(principal), raw, 900);
    }
    private String rawToken() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record User(UUID id,String email,String passwordHash,String role) { }
    private record Token(UUID id,UUID userId,UUID familyId,Instant expiresAt,Instant revokedAt) { }
    public static class InvalidCredentialsException extends RuntimeException { }
}
