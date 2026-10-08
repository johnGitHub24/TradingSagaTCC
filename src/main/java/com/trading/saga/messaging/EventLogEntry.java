package com.trading.saga.messaging;

import java.time.Instant;

/**
 * 【職責】前台 Kafka 軌跡一筆：記錄「某則 {@link SagaMessage} 已成功送到某個 topic」。
 * <p>【技巧】不可變 record；由 {@code EventLogService.record} 建立並放進記憶體 ring buffer（上限 100 筆，新的在前）。
 * <p>【概念】只有 {@code KafkaTemplateMessageSender.send} 拿到 broker ack 之後才會記一筆，
 * <br>所以這裡看到的是「真的寄出去的信」（命令經 Outbox Relay、事件由帳戶側直送），不是「已被消費」的證明。
 * <p>【使用】{@code GET /api/v1/events}（{@code EventLogController}）與 {@link com.trading.saga.order.dto.DemoStateResponse#events()}；
 * <br>Case OUTBOX-001 以軌跡出現 RESERVE_FUNDS 佐證 Outbox 已轉送。
 * <p>【邊界】不持久化，重啟即空；不是稽核紀錄，正式觀測應看 Kafka 本身或 tracing。
 *
 * @param topic   送達的 topic（command topic 或 event topic）
 * @param type    訊息型別（{@link SagaMessageTypes} 常數），前台用來顯示「走到哪一步」
 * @param sagaId  所屬流程，可用來篩出同一筆交易的全部訊息
 * @param payload 訊息內容的文字形式；目前取 {@code SagaMessage.toString()}（record 預設格式），僅供人眼閱讀、不是 JSON
 * @param at      記錄時間（送出成功當下，UTC）
 */
public record EventLogEntry(
        String topic,
        String type,
        String sagaId,
        String payload,
        Instant at
) {
}
