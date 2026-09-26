package io.jobplatform.apikeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import io.jobplatform.jobs.JobConflictException;
import io.jobplatform.jobs.JobNotFoundException;
import io.jobplatform.projects.ProjectService;
import io.jobplatform.security.ProductPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {
    private static final String KEY_PREFIX = "jpk_";
    private static final int PREFIX_LENGTH = 16;

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbc, ProjectService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    @Transactional
    public CreatedApiKey create(UUID projectId, UUID userId, CreateApiKeyRequest request) {
        projects.requireOwnership(projectId, userId);
        Boolean nameTaken = jdbc.queryForObject("select exists(select 1 from api_clients where project_id=? and name=?)",
                Boolean.class, projectId, request.name());
        if (Boolean.TRUE.equals(nameTaken)) {
            throw new JobConflictException("API_KEY_NAME_EXISTS", "An API key with this name already exists in the project.");
        }

        Instant now = Instant.now();
        UUID keyId = UUID.randomUUID();
        String plaintext = plaintextKey();
        String prefix = plaintext.substring(0, PREFIX_LENGTH);
        try {
            jdbc.update("""
                    insert into api_clients (id, project_id, name, key_prefix, key_hash, status, created_at)
                    values (?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, keyId, projectId, request.name(), prefix, hash(plaintext), Timestamp.from(now));
        } catch (DataIntegrityViolationException exception) {
            throw new JobConflictException("API_KEY_NAME_EXISTS", "An API key with this name already exists in the project.");
        }
        audit(projectId, "USER", userId, "API_KEY_CREATED", keyId, "{}");
        return new CreatedApiKey(keyId, request.name(), prefix, "ACTIVE", now, null, plaintext);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyMetadata> list(UUID projectId, UUID userId) {
        projects.requireOwnership(projectId, userId);
        return jdbc.query("""
                select id, name, key_prefix, status, created_at, last_used_at
                from api_clients where project_id = ? order by created_at desc, id desc
                """, (rs, rowNumber) -> new ApiKeyMetadata(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("key_prefix"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()), projectId);
    }

    @Transactional
    public void revoke(UUID keyId, UUID userId) {
        ApiClient client = jdbc.query("""
                select c.id, c.project_id, c.status
                from api_clients c join projects p on p.id = c.project_id
                where c.id = ? and p.owner_id = ? and p.status = 'ACTIVE' for update
                """, rs -> rs.next() ? new ApiClient(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)) : null,
                keyId, userId);
        if (client == null) throw new JobNotFoundException("API key not found.");
        if (!"REVOKED".equals(client.status())) {
            jdbc.update("update api_clients set status='REVOKED', revoked_at=? where id=?", Timestamp.from(Instant.now()), keyId);
            audit(client.projectId(), "USER", userId, "API_KEY_REVOKED", keyId, "{}");
        }
    }

    /** Returns no principal for malformed, unknown, revoked, or mismatched keys. */
    @Transactional
    public ProductPrincipal authenticate(String plaintext) {
        if (plaintext == null || plaintext.length() < PREFIX_LENGTH || plaintext.length() > 128) return null;
        String prefix = plaintext.substring(0, PREFIX_LENGTH);
        ApiClient client = jdbc.query("""
                select id, project_id, status, key_hash from api_clients where key_prefix = ?
                """, rs -> rs.next() ? new ApiClient(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4)) : null, prefix);
        if (client == null || !"ACTIVE".equals(client.status()) || !MessageDigest.isEqual(
                client.keyHash().getBytes(StandardCharsets.US_ASCII), hash(plaintext).getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        Instant now = Instant.now();
        jdbc.update("update api_clients set last_used_at=? where id=?", Timestamp.from(now), client.id());
        audit(client.projectId(), "API_KEY", client.id(), "API_KEY_USED", client.id(), "{}");
        return ProductPrincipal.apiKey(client.projectId(), client.id());
    }

    private String plaintextKey() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private void audit(UUID projectId, String actorType, UUID actorId, String eventType, UUID keyId, String metadata) {
        jdbc.update("""
                insert into audit_events (id, project_id, actor_type, actor_id, event_type, resource_type, resource_id, metadata, created_at)
                values (?, ?, ?, ?, ?, 'API_KEY', ?, cast(? as jsonb), ?)
                """, UUID.randomUUID(), projectId, actorType, actorId, eventType, keyId, metadata, Timestamp.from(Instant.now()));
    }

    private record ApiClient(UUID id, UUID projectId, String status, String keyHash) {
        private ApiClient(UUID id, UUID projectId, String status) { this(id, projectId, status, null); }
    }
}
