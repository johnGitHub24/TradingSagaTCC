package com.trading.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * 【職責】TradingSagaTCC 入口：雙庫手動組裝，排除單一 DataSource 自動設定。
 * 【技巧】{@code exclude} 三個自動設定：
 *         <ul>
 *           <li>{@link DataSourceAutoConfiguration}：不讀 {@code spring.datasource.*}，
 *               改由 {@code OrderDataSourceConfig}／{@code AccountDataSourceConfig} 讀 {@code trading.datasource.order／account}。</li>
 *           <li>{@link HibernateJpaAutoConfiguration}：不建預設 EntityManagerFactory，兩庫各有自己的 EMF 與套件掃描範圍。</li>
 *           <li>{@link DataSourceTransactionManagerAutoConfiguration}：不建預設交易管理器，
 *               改用 {@code orderTransactionManager}／{@code accountTransactionManager} 兩把 Local TX。</li>
 *         </ul>
 * 【概念】禁止預設「一個 DataSource 打遍所有 Entity」，否則雙庫邊界會被 Hibernate 混在一起。
 * 【使用】本機用 Gradle {@code bootRun}（勿對本類按 IDE 綠箭頭），預設埠 8093、內嵌 Kafka 一併啟動。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
public class TradingSagaTccApplication {

    /**
     * 啟動應用。
     *
     * @param args 命令列（可用 {@code --key=value} 覆寫 yml，例如 {@code --trading.kafka.embedded=false}）
     */
    public static void main(String[] args) {
        SpringApplication.run(TradingSagaTccApplication.class, args);
    }
}
