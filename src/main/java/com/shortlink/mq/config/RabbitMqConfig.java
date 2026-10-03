package com.shortlink.mq.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shortlink.config.ShortLinkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（V2）。
 *
 * 拓扑：生产者 -> 业务交换机(Direct) -> 业务队列 -> 消费者
 * 队列绑定死信交换机：消费重试 3 次仍失败的消息进入死信队列，避免无限重试。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shortlink.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    private final ShortLinkProperties properties;

    @Bean
    public DirectExchange visitExchange() {
        return new DirectExchange(properties.getMq().getExchange(), true, false);
    }

    @Bean
    public DirectExchange visitDeadLetterExchange() {
        return new DirectExchange(properties.getMq().getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue visitQueue() {
        return QueueBuilder.durable(properties.getMq().getQueue())
                .deadLetterExchange(properties.getMq().getDeadLetterExchange())
                .deadLetterRoutingKey(properties.getMq().getDeadLetterQueue())
                .build();
    }

    @Bean
    public Queue visitDeadLetterQueue() {
        return QueueBuilder.durable(properties.getMq().getDeadLetterQueue()).build();
    }

    @Bean
    public Binding visitBinding() {
        return BindingBuilder.bind(visitQueue()).to(visitExchange()).with(properties.getMq().getRoutingKey());
    }

    @Bean
    public Binding visitDeadLetterBinding() {
        return BindingBuilder.bind(visitDeadLetterQueue()).to(visitDeadLetterExchange())
                .with(properties.getMq().getDeadLetterQueue());
    }

    /**
     * 消息体用 JSON 序列化：可在 RabbitMQ 管理台直接阅读消息内容，便于排查
     */
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
