package com.trading.saga.messaging;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 【職責】Kafka 軌跡查詢。
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventLogController {

    private final EventLogService eventLogService;

    /**
     * @param eventLogService 記憶體軌跡
     */
    public EventLogController(EventLogService eventLogService) {
        this.eventLogService = eventLogService;
    }

    /**
     * 新到舊。
     */
    @GetMapping
    public List<EventLogEntry> list() {
        return eventLogService.list();
    }
}
