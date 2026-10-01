package com.trading.saga.messaging;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 【職責】Kafka 軌跡查詢。
 * 【概念】薄 Controller：只把 {@link EventLogService} 的記憶體清單轉成 HTTP JSON，不含商業規則。
 *         軌跡記的是「已成功送進 Kafka」的訊息（由 {@link KafkaTemplateMessageSender} 寫入），
 *         讓學習者看見 Saga command／event 實際走過哪些 type。
 * 【邊界】唯讀、無分頁；資料在記憶體，重啟即空，不是正式稽核來源。
 *
 * <p>【怎麼運作】{@code @RestController} + 建構子注入 {@link EventLogService}。
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventLogController {

    private final EventLogService eventLogService;

    /**
     * 【職責】注入記憶體軌跡（建構子注入）。
     *
     * @param eventLogService 記憶體軌跡
     */
    public EventLogController(EventLogService eventLogService) {
        this.eventLogService = eventLogService;
    }

    /**
     * 【職責】列出最近送出的 Kafka 訊息，新到舊。
     * 【使用】{@code GET /api/v1/events}；永遠 200（沒有資料時回空陣列）。
     *
     * @return 最多 100 筆軌跡（topic、type、sagaId、payload、at）
     */
    @GetMapping
    public List<EventLogEntry> list() {
        return eventLogService.list();
    }
}
