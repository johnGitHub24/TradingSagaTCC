package com.trading.saga.messaging;

import com.trading.saga.expansion.TccResource;
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
 * 【職責】帳戶 command handler 單元：Try 成功發 RESERVED、失敗發 FAILED。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCommandHandler unit")
class AccountCommandHandlerTest {

    @Mock
    private TccResource tccResource;
    @Mock
    private KafkaMessageSender kafkaMessageSender;

    @Test
    @DisplayName("SAGA-001: RESERVE_FUNDS success → FUNDS_RESERVED")
    void reserve_ok() {
        given(tccResource.tryReserve(eq("s1"), eq("ACC-001"), any())).willReturn(true);
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        SagaMessage cmd = SagaMessage.of(
                "s1", "o1", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("10000"), "BTCUSDT", false);

        handler.onMessage(cmd);

        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        verify(kafkaMessageSender).send(eq("trading.saga.events"), eq("s1"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.FUNDS_RESERVED);
    }

    @Test
    @DisplayName("SAGA-002: RESERVE_FUNDS fail → FUNDS_FAILED")
    void reserve_fail() {
        given(tccResource.tryReserve(eq("s2"), eq("ACC-001"), any())).willReturn(false);
        AccountCommandHandler handler = new AccountCommandHandler(
                tccResource, kafkaMessageSender, "trading.saga.events");
        SagaMessage cmd = SagaMessage.of(
                "s2", "o2", "ACC-001", SagaMessageTypes.RESERVE_FUNDS,
                new BigDecimal("999999"), "BTCUSDT", false);

        handler.onMessage(cmd);

        ArgumentCaptor<SagaMessage> captor = ArgumentCaptor.forClass(SagaMessage.class);
        verify(kafkaMessageSender).send(eq("trading.saga.events"), eq("s2"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(SagaMessageTypes.FUNDS_FAILED);
    }
}
