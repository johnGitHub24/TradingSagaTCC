package com.trading.saga.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 【職責】訂單庫 DataSource／EMF／Tx：orders、saga、outbox。
 * 【技巧】{@code @Primary} 給 H2 Console 與預設 JPA 探測；帳戶庫另檔組裝。
 * 【概念】這條邊界寫入不得出現帳戶 Entity。
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.trading.saga.order.infrastructure",
        entityManagerFactoryRef = "orderEntityManagerFactory",
        transactionManagerRef = "orderTransactionManager"
)
public class OrderDataSourceConfig {

    /**
     * 訂單庫連線屬性。
     */
    @Bean
    @Primary
    @ConfigurationProperties("trading.datasource.order")
    public DataSourceProperties orderDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 訂單庫連線池。
     */
    @Bean
    @Primary
    public DataSource orderDataSource(
            @Qualifier("orderDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 訂單庫 EMF，只掃 order 套件。
     */
    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean orderEntityManagerFactory(
            @Qualifier("orderDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.trading.saga.order");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(jpaProperties());
        factory.setPersistenceUnitName("orderPU");
        return factory;
    }

    /**
     * 訂單庫交易管理器（Outbox 必須掛這條）。
     */
    @Bean
    @Primary
    public PlatformTransactionManager orderTransactionManager(
            @Qualifier("orderEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    private static Map<String, Object> jpaProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "update");
        props.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        return props;
    }
}
