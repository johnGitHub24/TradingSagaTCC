package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 trade_orders 存取。
 */
public interface TradeOrderRepository extends JpaRepository<TradeOrder, String> {

    /**
     * @return 新到舊
     */
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
}
