package io.jobplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobplatform.observability.PlatformMetrics;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final boolean enabled;
    private final PlatformMetrics metrics;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, ObjectMapper json,
                           @Value("${app.dispatch.enabled:true}") boolean enabled, PlatformMetrics metrics) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.json = json; this.enabled = enabled; this.metrics = metrics;
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
            metrics.outboxPublished(event.type());
            log.debug("event=outbox_event_published eventId={} type={} routingKey={}", event.id(), event.type(), routingKey);
        } catch (Exception exception) {
            jdbc.update("update outbox_events set publish_attempts=publish_attempts+1, last_error=?, locked_until=? where id=?",
                    safeError(exception), Timestamp.from(Instant.now().plusSeconds(5)), event.id());
            metrics.outboxFailed(event.type());
            log.warn("event=outbox_event_publish_failed eventId={} type={} error={}", event.id(), event.type(),
                    exception.getClass().getSimpleName());
        }
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        String value = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return value.substring(0, Math.min(value.length(), 512));
    }

    private record OutboxEvent(UUID id, String type, String payload) { }
}
