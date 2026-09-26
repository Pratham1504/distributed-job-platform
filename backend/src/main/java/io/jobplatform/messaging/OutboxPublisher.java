package io.jobplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final boolean enabled;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper json,
                           @Value("${app.dispatch.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.json = json; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.dispatch.poll-interval-ms:1000}")
    public void publishScheduled() {
        if (enabled) publishAvailable();
    }

    @Transactional
    public void publishAvailable() {
        OutboxEvent event = jdbc.query("""
                select id, event_type, payload from outbox_events
                where published_at is null and (locked_until is null or locked_until < current_timestamp)
                order by created_at for update skip locked limit 1
                """, rs -> rs.next() ? new OutboxEvent(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)) : null);
        if (event == null) return;
        try {
            JsonNode payload = json.readTree(event.payload());
            String routingKey = "JOB_DEAD_LETTERED".equals(event.type()) ? "job.dead-letter"
                    : "job.ready." + payload.path("priority").asText().toLowerCase();
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId(event.id().toString());
            properties.setCorrelationId(payload.path("correlationId").asText());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            CorrelationData confirmation = new CorrelationData(event.id().toString());
            rabbit.send(RabbitTopologyConfiguration.JOB_EVENTS, routingKey,
                    new Message(event.payload().getBytes(), properties), confirmation);
            CorrelationData.Confirm confirm = confirmation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ rejected outbox event " + event.id());
            jdbc.update("update outbox_events set published_at=?, publish_attempts=publish_attempts+1, locked_until=null, last_error=null where id=?",
                    Timestamp.from(Instant.now()), event.id());
        } catch (Exception exception) {
            jdbc.update("update outbox_events set publish_attempts=publish_attempts+1, last_error=?, locked_until=? where id=?",
                    exception.getMessage(), Timestamp.from(Instant.now().plusSeconds(5)), event.id());
        }
    }

    private record OutboxEvent(UUID id, String type, String payload) { }
}
