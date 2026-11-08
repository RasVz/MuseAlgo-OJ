package com.musealgo.oj.analytics.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Preserve failed legacy notifications without changing the existing queue's declaration arguments. */
@Configuration
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class TagRatingMessagingConfiguration {
    public static final String FAILED_QUEUE = "oj.rating.failed";

    @Bean
    Queue tagRatingFailedQueue() { return new Queue(FAILED_QUEUE, true); }

    @Bean
    SimpleRabbitListenerContainerFactory tagRatingListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connections,
            RabbitTemplate template) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connections);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(3)
                .backOffOptions(200, 2, 1000)
                .recoverer((message, failure) -> {
                    var properties = new MessageProperties();
                    properties.setContentType(message.getMessageProperties().getContentType());
                    properties.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                    properties.setHeader("failureReason", "TAG_RATING_PROCESSING_FAILED");
                    // Keep the original event, without SQL, stack traces or raw dependency errors.
                    template.send("", FAILED_QUEUE, new Message(message.getBody(), properties));
                }).build());
        return factory;
    }
}
