package top.everblue.oj.judge.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ topology is opt-in until a broker and worker deployment are available. */
@Configuration
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class RabbitJudgeTopologyConfiguration {

    public static final String JUDGE_EXCHANGE = "oj.judge";
    public static final String JUDGE_QUEUE = "oj.judge.submit";
    public static final String RESULT_EXCHANGE = "oj.event";
    public static final String RANK_QUEUE = "oj.rank.update";
    public static final String RATING_QUEUE = "oj.rating.update";  // ELO 评分消费
    public static final String DEAD_LETTER_EXCHANGE = "oj.dlx";
    public static final String DEAD_LETTER_QUEUE = "oj.dlq";

    @Bean
    DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange resultExchange() {
        return new TopicExchange(RESULT_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", JUDGE_QUEUE)
                .build();
    }

    @Bean
    Queue rankQueue() {
        return QueueBuilder.durable(RANK_QUEUE).build();
    }

    @Bean
    Queue ratingQueue() {
        return QueueBuilder.durable(RATING_QUEUE).build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue()).to(judgeExchange()).with("submission.request");
    }

    @Bean
    Binding rankBinding() {
        return BindingBuilder.bind(rankQueue()).to(resultExchange()).with("submission.judged");
    }

    @Bean
    Binding ratingBinding() {
        return BindingBuilder.bind(ratingQueue()).to(resultExchange()).with("submission.judged");
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(JUDGE_QUEUE);
    }
}
