package com.trading.saga.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 【職責】多執行緒排程池，承載 Outbox Relay（預留第二條補償／逾時 Job）。
 * <p>【技巧】pool-size ≥ 2，避免未來第二個 {@code @Scheduled} 被單執行緒堵住。
 * <p>【概念】{@code @EnableScheduling} 讓 Spring 掃描 {@code @Scheduled}（目前只有
 * <br>{@link com.trading.saga.messaging.OutboxRelayJob#tick}，間隔 {@code trading.outbox.poll-ms}）；
 * <br>實作 {@link SchedulingConfigurer} 則把預設的單執行緒排程器換成本類的池。
 * <br>公版規範：多個 {@code @Scheduled} 時必須用 {@link ThreadPoolTaskScheduler}（pool-size ≥ 2）。
 * <p>【邊界】無條件生效、不綁 property（池大小寫死 2）；Job 只呼叫 Service，不在這裡定義任務。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    /**
     * 【職責】Outbox／未來補償掃描共用的 scheduler。
     * <p>【技巧】執行緒名前綴 {@code saga-sched-}，在 log／thread dump 一眼分辨排程執行緒；
     * <br>關機時等待執行中的任務最多 10 秒，避免 Outbox 轉送寫到一半被硬切。
     *
     * @return 已初始化的雙執行緒排程器
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("saga-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * 【職責】把 {@link #taskScheduler()} 指定給所有 {@code @Scheduled} 任務。
     * <p>【概念】這裡呼叫 {@code taskScheduler()} 不會再 new 一個：{@code @Configuration} 類別預設被 CGLIB 代理，
     * <br>Bean 方法回傳的是容器中同一個 singleton。
     *
     * @param taskRegistrar Spring 排程任務登錄器
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }
}
