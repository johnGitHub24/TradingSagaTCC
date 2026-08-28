package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 outbox_events 存取。
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Relay 拉取未發送列。
     *
     * @return 舊到新
     */
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
