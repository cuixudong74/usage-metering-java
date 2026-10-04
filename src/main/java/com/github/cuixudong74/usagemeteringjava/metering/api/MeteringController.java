package com.github.cuixudong74.usagemeteringjava.metering.api;

import com.github.cuixudong74.usagemeteringjava.metering.MeteringService;
import com.github.cuixudong74.usagemeteringjava.metering.model.IngestResult;
import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.model.WindowAggregate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** 计量本地接口：事件上报与窗口查询。 */
@RestController
@RequestMapping("/api/metering")
public class MeteringController {

    private final MeteringService service;

    public MeteringController(MeteringService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public IngestResult report(@RequestBody UsageEvent event) {
        return service.report(event);
    }

    @GetMapping("/trace/{eventId}")
    public String trace(@PathVariable String eventId) {
        return service.traceOf(eventId).orElse("event not found: " + eventId);
    }

    @GetMapping("/windows")
    public List<WindowAggregate> query(@RequestParam String tenantId,
                                       @RequestParam String dimension,
                                       @RequestParam Instant from,
                                       @RequestParam Instant to) {
        return service.query(tenantId, dimension, from, to);
    }
}
