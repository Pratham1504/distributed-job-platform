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
import org.springframework.transaction.annotation.Propagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class IdentityService {
    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtTokenService jwt;
    private final Duration refreshTtl;
    private final OperatorRolePolicy operatorRoles;
    private final SecureRandom random = new SecureRandom();
    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtTokenService jwt,
                           OperatorRolePolicy operatorRoles,
                           @Value("${app.security.refresh-token-ttl}") Duration refreshTtl) {
        this.jdbc = jdbc; this.passwords = passwords; this.jwt = jwt; this.operatorRoles = operatorRoles; this.refreshTtl = refreshTtl;
    }
    @Transactional
    public AuthSession register(RegisterRequest request) {
        UUID userId = UUID.randomUUID(); UUID projectId = UUID.randomUUID(); Instant now = Instant.now();
        String email = request.email().toLowerCase();
        String role = operatorRoles.roleFor(email);
        try {
            jdbc.update("insert into users (id,email,password_hash,role,created_at) values (?,?,?,?,?)", userId, email, passwords.encode(request.password()), role, Timestamp.from(now));
            jdbc.update("insert into projects (id,owner_id,name,status,created_at) values (?,?,?,?,?)", projectId, userId, request.projectName(), "ACTIVE", Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) { throw new JobConflictException("EMAIL_ALREADY_REGISTERED", "An account with this email already exists."); }
        insertAudit(null, "USER", userId, "AUTH_REGISTERED", "USER", userId, now);
        log.info("event=user_registered userId={} projectId={} role={}", userId, projectId, role);
        return createSession(new ProductPrincipal(userId, email, role), null, null).session();
    }
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthSession login(AuthRequest request) {
        User user = jdbc.query("select id,email,password_hash,role from users where email = ?", rs -> rs.next() ? new User(rs.getObject(1, UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)) : null, request.email().toLowerCase());
        if (user == null || !passwords.matches(request.password(), user.passwordHash())) {
            insertAudit(null, "ANONYMOUS", null, "AUTH_LOGIN_REJECTED", "AUTH", null, Instant.now());
            log.warn("event=login_rejected reason=invalid_credentials");
            throw new InvalidCredentialsException();
        }
        String role = operatorRoles.roleFor(user.email());
        if (!role.equals(user.role())) jdbc.update("update users set role=? where id=?", role, user.id());
        insertAudit(null, "USER", user.id(), "AUTH_LOGIN_SUCCEEDED", "USER", user.id(), Instant.now());
        log.info("event=user_logged_in userId={}", user.id());
        return createSession(new ProductPrincipal(user.id(), user.email(), role), null, null).session();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRateLimitedLogin() {
        insertAudit(null, "ANONYMOUS", null, "AUTH_LOGIN_RATE_LIMITED", "AUTH", null, Instant.now());
    }
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public AuthSession refresh(RefreshRequest request) {
        String hash = hash(request.refreshToken());
        Token token = jdbc.query("select id,user_id,family_id,expires_at,revoked_at from refresh_tokens where token_hash=? for update", rs -> rs.next() ? new Token(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()) : null, hash);
        if (token == null || token.revokedAt() != null || token.expiresAt().isBefore(Instant.now())) {
            if (token != null) jdbc.update("update refresh_tokens set revoked_at=current_timestamp where family_id=? and revoked_at is null", token.familyId());
            insertAudit(null, "ANONYMOUS", token == null ? null : token.userId(), "AUTH_REFRESH_REJECTED", "AUTH", null, Instant.now());
            log.warn("event=refresh_rejected reason=invalid_or_reused_token");
            throw new InvalidCredentialsException();
        }
        User user = jdbc.query("select id,email,password_hash,role from users where id=?", rs -> rs.next() ? new User(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)) : null, token.userId());
        if (user == null) throw new InvalidCredentialsException();
        String role = operatorRoles.roleFor(user.email());
        if (!role.equals(user.role())) jdbc.update("update users set role=? where id=?", role, user.id());
        CreatedSession next = createSession(new ProductPrincipal(user.id(), user.email(), role), token.familyId(), token.id());
        jdbc.update("update refresh_tokens set revoked_at=current_timestamp,last_used_at=current_timestamp,replaced_by_token_id=? where id=?", next.tokenId(), token.id());
        insertAudit(null, "USER", user.id(), "AUTH_REFRESH_SUCCEEDED", "USER", user.id(), Instant.now());
        log.info("event=session_refreshed userId={}", user.id());
        return next.session();
    }
    private CreatedSession createSession(ProductPrincipal principal, UUID familyId, UUID parentTokenId) {
        String raw = rawToken(); UUID tokenId = UUID.randomUUID(); UUID family = familyId == null ? UUID.randomUUID() : familyId; Instant now=Instant.now();
        jdbc.update("insert into refresh_tokens (id,user_id,family_id,parent_token_id,token_hash,issued_at,expires_at) values (?,?,?,?,?,?,?)", tokenId,principal.userId(),family,parentTokenId,hash(raw),Timestamp.from(now),Timestamp.from(now.plus(refreshTtl)));
        return new CreatedSession(new AuthSession(jwt.create(principal), raw, 900), tokenId);
    }
    private void insertAudit(UUID projectId, String actorType, UUID actorId, String eventType, String resourceType, UUID resourceId, Instant now) {
        jdbc.update("""
                insert into audit_events (id,project_id,actor_type,actor_id,event_type,resource_type,resource_id,metadata,created_at)
                values (?,?,?,?,?,?,?,cast('{}' as jsonb),?)
                """, UUID.randomUUID(), projectId, actorType, actorId, eventType, resourceType, resourceId, Timestamp.from(now));
    }
    private String rawToken() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private record User(UUID id,String email,String passwordHash,String role) { }
    private record Token(UUID id,UUID userId,UUID familyId,Instant expiresAt,Instant revokedAt) { }
    private record CreatedSession(AuthSession session, UUID tokenId) { }
    public static class InvalidCredentialsException extends RuntimeException { }
}
