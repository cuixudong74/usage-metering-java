package com.example.metering.api;

import com.example.metering.core.MeteringService;
import com.example.metering.core.WindowAggregator;
import com.example.metering.model.LateEventRecord;
import com.example.metering.model.QuotaDecision;
import com.example.metering.model.QuotaPolicy;
import com.example.metering.model.ReconcileReport;
import com.example.metering.model.UsageEvent;
import com.example.metering.model.WindowAggregate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MeteringController {

    private final MeteringService service;

    public MeteringController(MeteringService service) {
        this.service = service;
    }

    public record IngestResponse(String eventId, WindowAggregator.IngestResult result) {
    }

    @PostMapping("/events")
    public IngestResponse report(@RequestBody UsageEvent event) {
        return new IngestResponse(event.eventId(), service.report(event));
    }

    @PostMapping("/events/batch")
    public List<IngestResponse> reportBatch(@RequestBody List<UsageEvent> events) {
        return events.stream().map(this::report).toList();
    }

    @GetMapping("/aggregates")
    public List<WindowAggregate> aggregates(@RequestParam String tenantId,
                                            @RequestParam String metric) {
        return service.query(tenantId, metric);
    }

    @GetMapping("/late-events")
    public List<LateEventRecord> lateEvents(@RequestParam String tenantId) {
        return service.lateEvents(tenantId);
    }

    @PostMapping("/quota/check")
    public QuotaDecision checkQuota(@RequestParam String tenantId,
                                    @RequestParam String metric,
                                    @RequestParam(defaultValue = "0") long requested) {
        return service.checkQuota(tenantId, metric, requested);
    }

    @PutMapping("/quota/policies")
    public QuotaPolicy upsertPolicy(@RequestBody QuotaPolicy policy) {
        service.upsertPolicy(policy);
        return policy;
    }

    @GetMapping("/quota/policies")
    public List<QuotaPolicy> policies() {
        return service.policies();
    }

    @GetMapping("/quota/decisions")
    public List<QuotaDecision> decisions() {
        return service.decisionLog();
    }

    @PostMapping("/reconcile")
    public ReconcileReport reconcile(@RequestBody List<UsageEvent> historicalBatch) {
        return service.reconcile(historicalBatch);
    }
}
