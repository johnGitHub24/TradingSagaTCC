package com.trading.saga.order;

import com.trading.saga.common.ResourceNotFoundException;
import com.trading.saga.order.infrastructure.SagaInstanceRepository;
import com.trading.saga.order.infrastructure.SagaStepRepository;
import com.trading.saga.order.infrastructure.TradeOrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 【職責】查單單元層，與 TRADE-001 成對（不存在 → 404 語意例外）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeQueryService unit")
class TradeQueryServiceTest {

    @Mock
    private TradeOrderRepository orderRepository;
    @Mock
    private SagaInstanceRepository sagaInstanceRepository;
    @Mock
    private SagaStepRepository sagaStepRepository;
    @InjectMocks
    private TradeQueryService queryService;

    @Test
    @DisplayName("TRADE-001: get unknown order → ResourceNotFoundException")
    void getOrder_unknown_throws() {
        given(orderRepository.findById("missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.getOrder("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }
}
