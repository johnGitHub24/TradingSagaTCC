package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.TradeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 trade_orders 存取。只做查詢／寫入，不含商業規則（狀態轉換在 {@link TradeOrder} 實體方法內）。
 * 【技巧】繼承 {@link JpaRepository}（主鍵型別 String＝orderId）由 Spring Data 自動實作；
 * 內建 {@code save}／{@code findById} 已涵蓋大部分需求，只多一支排序列表查詢。
 * 【概念】綁定 orderTransactionManager（見 {@code OrderDataSourceConfig}）；帳戶庫的 Service 不可注入本介面。
 * 【使用】{@code SagaOrchestrator}（save）、{@code OrderSagaEventHandler}／{@code OrderMarkFailedAction}（findById 後改狀態，
 * 靠 JPA dirty checking 在 TX commit 時寫回）、{@code TradeQueryService}（查詢）。
 */
public interface TradeOrderRepository extends JpaRepository<TradeOrder, String> {

    /**
     * 【職責】列出全部訂單，最新的在前。
     * 【技巧】Spring Data 命名推導：{@code findAllBy}＋{@code OrderByCreatedAtDesc}＝無 WHERE、依 created_at 降冪；
     * 方法名中的 {@code All} 與 {@code By} 之間沒有條件，所以是全表。
     * 【使用】{@code TradeQueryService.listOrders}（{@code GET /api/v1/trades} 與 Demo 面板）。
     * 【邊界】不分頁；Demo 資料量小可接受，正式環境應改 {@code Pageable}。
     *
     * @return 全部訂單，新到舊；無資料時為空列表
     */
    List<TradeOrder> findAllByOrderByCreatedAtDesc();
}
