package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.SagaStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 saga_steps 存取。
 */
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {

    /**
     * @param sagaId 流程 id
     * @return 時間序
     */
    List<SagaStep> findBySagaIdOrderByAtAscIdAsc(String sagaId);
}
