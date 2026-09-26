package io.jobplatform.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfiguration {
    public static final String JOB_EVENTS = "job.events";

    @Bean TopicExchange jobEventsExchange() { return new TopicExchange(JOB_EVENTS, true, false); }
    @Bean Queue highJobsQueue() { return new Queue("jobs.high", true); }
    @Bean Queue defaultJobsQueue() { return new Queue("jobs.default", true); }
    @Bean Queue lowJobsQueue() { return new Queue("jobs.low", true); }
    @Bean Queue retryJobsQueue() { return new Queue("jobs.retry", true); }
    @Bean Queue deadLetterJobsQueue() { return new Queue("jobs.dead-letter", true); }

    @Bean Binding highJobsBinding(Queue highJobsQueue, TopicExchange jobEventsExchange) {
        return BindingBuilder.bind(highJobsQueue).to(jobEventsExchange).with("job.ready.high");
    }
    @Bean Binding defaultJobsBinding(Queue defaultJobsQueue, TopicExchange jobEventsExchange) {
        return BindingBuilder.bind(defaultJobsQueue).to(jobEventsExchange).with("job.ready.default");
    }
    @Bean Binding lowJobsBinding(Queue lowJobsQueue, TopicExchange jobEventsExchange) {
        return BindingBuilder.bind(lowJobsQueue).to(jobEventsExchange).with("job.ready.low");
    }
    @Bean Binding deadLetterBinding(Queue deadLetterJobsQueue, TopicExchange jobEventsExchange) {
        return BindingBuilder.bind(deadLetterJobsQueue).to(jobEventsExchange).with("job.dead-letter");
    }
}
