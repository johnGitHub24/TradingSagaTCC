package com.trading.saga.saga;

import com.trading.saga.order.domain.OrderStatus;
import com.trading.saga.order.domain.SagaInstance;
import com.trading.saga.order.domain.SagaStatus;
import com.trading.saga.order.domain.TradeOrder;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 【職責】補償動作單元層：與 SAGA-002／TCC-002 同一「訂單 FAILED + Saga COMPENSATED」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderMarkFailedAction unit")
class OrderMarkFailedActionTest {

    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    @Mock
    private TradeOrderRepository orderRepository;
    @Mock
    private SagaStepRepository sagaStepRepository;
    @InjectMocks
    private OrderMarkFailedAction action;

    @Test
    @DisplayName("SAGA-002 / TCC-002: compensate marks order FAILED and saga COMPENSATED")
    void compensate_marksFailed() {
        SagaInstance saga = SagaInstance.start("saga-2", "order-2");
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        TradeOrder order = TradeOrder.pending(
                "order-2", "saga-2", "ACC-001", "BTCUSDT", "BUY",
                BigDecimal.ONE, new BigDecimal("999999"), false);
        given(sagaInstanceRepository.findById("saga-2")).willReturn(Optional.of(saga));
        given(orderRepository.findById("order-2")).willReturn(Optional.of(order));

        action.compensate("saga-2");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        verify(sagaStepRepository).save(any());
    }

    @Test
    @DisplayName("compensate is idempotent on terminal saga")
    void compensate_terminal_noop() {
        SagaInstance saga = SagaInstance.start("saga-9", "order-9");
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        saga.transitionTo(SagaStatus.COMPENSATING);
        saga.transitionTo(SagaStatus.COMPENSATED);
        given(sagaInstanceRepository.findById("saga-9")).willReturn(Optional.of(saga));

        action.compensate("saga-9");

        verify(orderRepository, never()).findById(any());
    }
}
