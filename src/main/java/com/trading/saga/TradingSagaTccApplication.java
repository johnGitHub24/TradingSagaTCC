package com.trading.saga;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * 【職責】TradingSagaTCC 入口：雙庫手動組裝，排除單一 DataSource 自動設定。
 * 【概念】禁止預設「一個 DataSource 打遍所有 Entity」，否則雙庫邊界會被 Hibernate 混在一起。
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
     * @param args 命令列
     */
    public static void main(String[] args) {
        SpringApplication.run(TradingSagaTccApplication.class, args);
    }
}
