package com.trading.saga.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 【職責】帳戶庫的 TCC 預留紀錄（俗稱「預留票」），以 sagaId 當自然鍵以利冪等 Try／Cancel。
 * 【技巧】同一 saga 重送 Reserve 時直接回既有列，不重複扣 available。
 * 狀態轉換寫成實體方法（{@link #markConfirmed}／{@link #markCancelled}）並自帶前置檢查，
 * 讓「CONFIRMED 後不能 Cancel」這類規則跟著資料走，Service 不必重抄。
 * 【概念】預留票＝帳戶側的 TCC 憑證（非訂單）：
 * Try 發票（available→frozen＋寫本列）；Confirm 兌票扣款（吃掉 frozen）；Cancel 退票還原。
 * 訂單庫看不到此表，只靠 Kafka 事件得知結果。
 * Try 失敗（餘額不足）<b>不寫</b>本列，所以「查無票」本身就代表「錢從未被凍結」。
 * 【使用】經 {@code AccountTccService}／Repository 操作；PK＝sagaId（冪等 Key）。
 * 【邊界】這是帳戶自己的「預留票」；不要與 trade_orders 混淆。
 * 本表無 {@code @Version}；併發保護依賴「key＝sagaId 的命令同分區依序消費」，不是靠樂觀鎖。
 */
@Entity
@Table(name = "tcc_reservations")
@Getter
@NoArgsConstructor
public class TccReservation {

    /** 主鍵＝sagaId（自然鍵、非自增）：同一 Saga 最多一張票，重送命令用 {@code findById(sagaId)} 就能判斷是否已處理過。 */
    @Id
    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    /** 被凍結的帳戶（同庫 {@code accounts.account_id}）；Confirm／Cancel 時用它回查帳戶，不依賴命令訊息再帶一次。 */
    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    /** Try 當下凍結的金額（BigDecimal、scale 4）；Confirm／Cancel 一律以此值增減 frozen，保證三步金額一致。 */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** 資金狀態 TRYING／CONFIRMED／CANCELLED；{@code EnumType.STRING} 存名稱，enum 增減不會讓舊列錯位。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TccState state;

    /** 發票（Try 成功）時間（UTC）；可用來找出長時間停在 TRYING、需要人工或逾時 Cancel 的票。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private TccReservation(String sagaId, String accountId, BigDecimal amount) {
        this.sagaId = sagaId;
        this.accountId = accountId;
        this.amount = amount;
        this.state = TccState.TRYING;
        this.createdAt = Instant.now();
    }

    /**
     * 【職責】建立 TRYING 預留（尚未 persist）。
     * 【概念】只在 {@code Account.tryReserve} 成功（available 已轉 frozen）之後建立；兩者在同一 account TX 內同進同退。
     * 【使用】Try 成功後 {@code reservationRepository.save(TccReservation.trying(...))}。
     *
     * @param sagaId    流程 id（成為本票主鍵）
     * @param accountId 被凍結的帳戶
     * @param amount    凍結金額
     * @return 狀態 TRYING 的新票
     */
    public static TccReservation trying(String sagaId, String accountId, BigDecimal amount) {
        return new TccReservation(sagaId, accountId, amount);
    }

    /**
     * 【職責】標記已 Confirm（TRYING → CONFIRMED）。
     * 【概念】CONFIRMED 是終態：錢已真正扣掉，之後不能再 Cancel。
     * 「已 CONFIRMED 再 Confirm」的冪等由 {@code AccountTccService.confirm} 先判斷後直接回 true，不會呼叫到這裡。
     * 【使用】僅 Confirm 成功路徑；非 TRYING 會丟 IllegalStateException。
     *
     * @throws IllegalStateException 目前不是 TRYING（例如已 CANCELLED）
     */
    public void markConfirmed() {
        if (state != TccState.TRYING) {
            throw new IllegalStateException("cannot confirm reservation in " + state);
        }
        this.state = TccState.CONFIRMED;
    }

    /**
     * 【職責】標記已 Cancel；已 CANCELLED 則冪等略過。
     * 【技巧】回傳布林值讓呼叫端知道「這次是否真的轉出」，只有 true 才把 frozen 還回 available，避免重送時重複退款。
     * 【使用】Cancel／forceFail 路徑；回 true 表示本次真正從 TRYING 轉出（才需還錢）。
     *
     * @return true 表示本次真正從 TRYING 轉出
     * @throws IllegalStateException 目前是 CONFIRMED（已扣款不能退票）
     */
    public boolean markCancelled() {
        if (state == TccState.CANCELLED) {
            return false;
        }
        if (state != TccState.TRYING) {
            throw new IllegalStateException("cannot cancel reservation in " + state);
        }
        this.state = TccState.CANCELLED;
        return true;
    }
}
