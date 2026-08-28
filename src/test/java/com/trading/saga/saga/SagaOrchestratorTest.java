package com.trading.saga.saga;

import com.trading.saga.account.AccountLookup;
import com.trading.saga.messaging.OutboxPort;
import com.trading.saga.messaging.SagaMessage;
import com.trading.saga.messaging.SagaMessageTypes;
import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.dto.TradeRequest;
import com.trading.saga.order.dto.TradeResponse;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 【職責】{@link SagaOrchestrator} 單元層：SAGA-001／OUTBOX-001 同一契約（寫 Outbox RESERVE_FUNDS）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SagaOrchestrator unit")
class SagaOrchestratorTest {

    @Mock
    private AccountLookup accountLookup;
    @Mock
    private TradeOrderRepository orderRepository;
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    @Mock
    private SagaStepRepository sagaStepRepository;
    @Mock
    private OutboxPort outboxPort;

    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SagaOrchestrator(
                accountLookup, orderRepository, sagaInstanceRepository, sagaStepRepository,
                outboxPort, "trading.saga.commands");
        given(orderRepository.save(any(TradeOrder.class))).willAnswer(inv -> inv.getArgument(0));
        given(sagaInstanceRepository.save(any(SagaInstance.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("SAGA-001 / OUTBOX-001: start appends RESERVE_FUNDS outbox and returns PENDING")
    void start_appendsReserveCommand() {
        TradeRequest request = new TradeRequest(
                "ACC-001", "BTCUSDT", "BUY",
                BigDecimal.ONE, new BigDecimal("10000"), false);

        TradeResponse response = orchestrator.start(request);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.amount()).isEqualByComparingTo("10000");
        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        verify(outboxPort).append(eq("trading.saga.commands"), any(), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.RESERVE_FUNDS);
        verify(accountLookup).requireExists("ACC-001");
        ArgumentCaptor<SagaInstance> sagaCaptor = ArgumentCaptor.forClass(SagaInstance.class);
        verify(sagaInstanceRepository).save(sagaCaptor.capture());
        assertThat(sagaCaptor.getValue().getStatus()).isEqualTo(SagaStatus.ACCOUNT_TRYING);
    }
}
