package com.trading.saga.order.infrastructure;

import com.trading.saga.order.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【職責】訂單庫 outbox_events（發件匣）存取。只做查詢／寫入，不含商業規則。
 * 【技巧】繼承 {@link JpaRepository}，Spring Data 啟動時自動產生實作；{@code save} 用於投遞進匣，
 * 自訂方法只有 Relay 拉待發列這一支，靠「方法命名推導」產生查詢，不寫 JPQL。
 * 【概念】本套件 {@code order.infrastructure} 由 {@code OrderDataSourceConfig} 的 {@code @EnableJpaRepositories}
 * 綁到 orderEntityManagerFactory／orderTransactionManager，所以寫入發件匣天然與訂單、Saga 同庫同 TX。
 * 【使用】{@code OutboxPublisherService}：{@code append} 呼叫 {@code save}；{@code publishPending} 呼叫下方查詢。
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 【職責】Relay 拉取一批未發送列。
     * 【技巧】Spring Data 命名推導：{@code findTop50}＝LIMIT 50；{@code ByPublishedAtIsNull}＝WHERE published_at IS NULL；
     * {@code OrderByIdAsc}＝依自增 id 由小到大（即寫入順序），讓先進匣的信先寄。
     * 【概念】一批上限 50 筆，避免積壓時單一 tick 的交易過大；剩下的留給下一輪排程。
     * 【使用】僅 {@code OutboxPublisherService.publishPending}（由 {@code OutboxRelayJob.tick} 每 {@code trading.outbox.poll-ms} 觸發）。
     *
     * @return 最多 50 筆待發列，舊到新；沒有待發時為空列表
     */
    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByIdAsc();
}
