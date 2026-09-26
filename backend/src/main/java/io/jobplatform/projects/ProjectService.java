package io.jobplatform.projects;

import io.jobplatform.jobs.JobNotFoundException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final JdbcTemplate jdbc;
    public ProjectService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true)
    public List<ProjectResponse> list(UUID userId) {
        return jdbc.query("select id,name,created_at from projects where owner_id=? and status='ACTIVE' order by created_at desc", (rs, row) -> new ProjectResponse(rs.getObject(1,UUID.class),rs.getString(2),rs.getTimestamp(3).toInstant()), userId);
    }
    @Transactional
    public ProjectResponse create(UUID userId, CreateProjectRequest request) {
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        try { jdbc.update("insert into projects (id,owner_id,name,status,created_at) values (?,?,?,?,?)",id,userId,request.name(),"ACTIVE",Timestamp.from(now)); }
        catch (DataIntegrityViolationException e) { throw new IllegalArgumentException("A project with this name already exists."); }
        return new ProjectResponse(id,request.name(),now);
    }
    public void requireOwnership(UUID projectId, UUID userId) {
        Boolean owned=jdbc.queryForObject("select exists(select 1 from projects where id=? and owner_id=? and status='ACTIVE')",Boolean.class,projectId,userId);
        if (!Boolean.TRUE.equals(owned)) throw new JobNotFoundException("Project not found.");
    }
}
