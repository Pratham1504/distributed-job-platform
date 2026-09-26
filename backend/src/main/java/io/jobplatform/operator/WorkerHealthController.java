package io.jobplatform.operator;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/workers")
public class WorkerHealthController {
    private final JdbcTemplate jdbc;

    public WorkerHealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<WorkerHealthResponse> list() {
        return jdbc.query("""
                select id, instance_name,
                       case when last_heartbeat_at < current_timestamp - interval '60 seconds' then 'STALE' else status end as health_status,
                       last_heartbeat_at
                from workers order by last_heartbeat_at desc, id desc
                """, (rs, rowNumber) -> new WorkerHealthResponse(rs.getObject("id", UUID.class), rs.getString("instance_name"),
                rs.getString("health_status"), rs.getTimestamp("last_heartbeat_at").toInstant()));
    }
}
