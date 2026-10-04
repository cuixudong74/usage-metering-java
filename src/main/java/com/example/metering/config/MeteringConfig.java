package com.example.metering.config;

import com.example.metering.core.EventJournal;
import com.example.metering.core.FileEventJournal;
import com.example.metering.core.InMemoryEventJournal;
import com.example.metering.core.MeteringService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(MeteringProperties.class)
public class MeteringConfig {

    @Bean
    public EventJournal eventJournal(MeteringProperties properties) {
        if (properties.getJournalPath() == null || properties.getJournalPath().isBlank()) {
            return new InMemoryEventJournal();
        }
        return new FileEventJournal(Path.of(properties.getJournalPath()));
    }

    @Bean
    public MeteringService meteringService(MeteringProperties properties, EventJournal journal) {
        return new MeteringService(properties, journal);
    }
}
