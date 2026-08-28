package com.trading.saga.order.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【職責】保護 Saga 狀態機合法轉移（編排終態與補償路徑）。
 * 【概念】成功：STARTED→TRYING→CONFIRMING→COMPLETED；失敗：任中段→COMPENSATING→COMPENSATED。
 */
@DisplayName("SagaStatus transitions")
class SagaStatusTest {

    @Test
    @DisplayName("SAGA-001 happy path transitions are allowed")
    void happyPath_allowed() {
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.ACCOUNT_TRYING)).isTrue();
        assertThat(SagaStatus.STARTED.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        assertThat(SagaStatus.ACCOUNT_TRYING.canTransitionTo(SagaStatus.ACCOUNT_CONFIRMING)).isTrue();
        assertThat(SagaStatus.ACCOUNT_CONFIRMING.canTransitionTo(SagaStatus.COMPLETED)).isTrue();
        assertThat(SagaStatus.COMPLETED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("SAGA-002 / TCC-002 compensation transitions are allowed")
    void compensationPath_allowed() {
        assertThat(SagaStatus.ACCOUNT_TRYING.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        assertThat(SagaStatus.ACCOUNT_CONFIRMING.canTransitionTo(SagaStatus.COMPENSATING)).isTrue();
        assertThat(SagaStatus.COMPENSATING.canTransitionTo(SagaStatus.COMPENSATED)).isTrue();
        assertThat(SagaStatus.COMPENSATED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("COMPLETED cannot transition to COMPENSATING")
    void completed_cannotCompensate() {
        assertThat(SagaStatus.COMPLETED.canTransitionTo(SagaStatus.COMPENSATING)).isFalse();
        SagaInstance saga = SagaInstance.start("saga-1", "order-1");
        saga.transitionTo(SagaStatus.ACCOUNT_TRYING);
        saga.transitionTo(SagaStatus.ACCOUNT_CONFIRMING);
        saga.transitionTo(SagaStatus.COMPLETED);
        assertThatThrownBy(() -> saga.transitionTo(SagaStatus.COMPENSATING))
                .isInstanceOf(IllegalStateException.class);
    }
}
