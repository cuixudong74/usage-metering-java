package com.example.metering.config;

import com.example.metering.core.LateEventPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "metering")
public class MeteringProperties {

    /** 聚合窗口大小。 */
    private Duration windowSize = Duration.ofHours(1);

    /** 允许的迟到时长（水位线宽限）。 */
    private Duration allowedLateness = Duration.ofMinutes(10);

    /** 窗口关闭后迟到事件处理策略。 */
    private LateEventPolicy lateEventPolicy = LateEventPolicy.REJECT;

    /** 事件日志文件路径（空则仅内存）。 */
    private String journalPath = "";

    public Duration getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(Duration windowSize) {
        this.windowSize = windowSize;
    }

    public Duration getAllowedLateness() {
        return allowedLateness;
    }

    public void setAllowedLateness(Duration allowedLateness) {
        this.allowedLateness = allowedLateness;
    }

    public LateEventPolicy getLateEventPolicy() {
        return lateEventPolicy;
    }

    public void setLateEventPolicy(LateEventPolicy lateEventPolicy) {
        this.lateEventPolicy = lateEventPolicy;
    }

    public String getJournalPath() {
        return journalPath;
    }

    public void setJournalPath(String journalPath) {
        this.journalPath = journalPath;
    }
}
