package com.github.cuixudong74.usagemeteringjava.metering.api;

import com.github.cuixudong74.usagemeteringjava.metering.model.UsageEvent;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayReport;
import com.github.cuixudong74.usagemeteringjava.metering.replay.ReplayService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 回放对账接口。 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping
    public ReplayReport replay(@RequestBody List<UsageEvent> events) {
        return replayService.replay(events);
    }
}
