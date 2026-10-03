package com.shortlink.mq.producer;

import com.shortlink.config.ShortLinkProperties;
import com.shortlink.mq.message.VisitMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 消息生产者测试：消息体构造、投递失败不影响跳转
 */
@ExtendWith(MockitoExtension.class)
class VisitMessageProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private VisitMessageProducer producer;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        producer = new VisitMessageProducer(rabbitTemplate, properties);
    }

    @Test
    void buildMessageFillsAllFields() {
        VisitMessage message = producer.buildMessage("abc123", "1.2.3.4", "UA", "https://www.baidu.com");
        assertNotNull(message.getVisitId());
        assertEquals("abc123", message.getCode());
        assertEquals("1.2.3.4", message.getClientIp());
        assertEquals("UA", message.getUserAgent());
        assertEquals("https://www.baidu.com", message.getReferer());
        assertNotNull(message.getVisitTime());
    }

    @Test
    void sendUsesConfiguredExchangeAndRoutingKey() {
        VisitMessage message = producer.buildMessage("abc123", "1.2.3.4", "UA", null);
        producer.send(message);
        verify(rabbitTemplate).convertAndSend("short-link.visit.exchange", "short-link.visit", message);
    }

    @Test
    void sendFailureDoesNotThrow() {
        VisitMessage message = producer.buildMessage("abc123", "1.2.3.4", "UA", null);
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
        assertDoesNotThrow(() -> producer.send(message));
    }
}
