package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.SagaInstance;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【職責】訂單庫 saga_instances 存取。
 */
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, String> {
}
