package com.trading.saga.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.saga.order.domain.OutboxEvent;
import com.trading.saga.order.infrastructure.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 【職責】Outbox 單元層：OUTBOX-001 append 為 unpublished，publishPending 才送 Kafka。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxPublisherService unit")
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Mock
    private KafkaMessageSender kafkaMessageSender;
    @InjectMocks
    private OutboxPublisherService publisherService;

    @Test
    @DisplayName("OUTBOX-001: append stores unpublished payload")
    void append_unpublished() {
        SagaMessage message = SagaMessage.of(
                "saga-1", "order-1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        publisherService.append("trading.saga.commands", "saga-1", message);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().isUnpublished()).isTrue();
        assertThat(captor.getValue().getTopic()).isEqualTo("trading.saga.commands");
        assertThat(captor.getValue().getPayload()).contains("RESERVE_FUNDS");
    }

    @Test
    @DisplayName("OUTBOX-001: publishPending sends Kafka then marks published")
    void publishPending_sends() throws Exception {
        SagaMessage message = SagaMessage.of(
                "saga-1", "order-1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);
        OutboxEvent event = OutboxEvent.unpublished(
                "trading.saga.commands", "saga-1", objectMapper.writeValueAsString(message));
        given(outboxEventRepository.findTop50ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(event));

        publisherService.publishPending();

        ArgumentCaptor<SagaMessage> sent = ArgumentCaptor.forClass(SagaMessage.class);
        verify(kafkaMessageSender).send(eq("trading.saga.commands"), eq("saga-1"), sent.capture());
        assertThat(sent.getValue().type()).isEqualTo(SagaMessageTypes.RESERVE_FUNDS);
        assertThat(sent.getValue().sagaId()).isEqualTo("saga-1");
        assertThat(event.isUnpublished()).isFalse();
    }
}
