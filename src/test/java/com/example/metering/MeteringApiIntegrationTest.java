package com.example.metering;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MeteringApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void reportQueryAndQuotaCheckOverHttp() throws Exception {
        String event = """
                {"eventId":"api-e1","tenantId":"tenant-api","metric":"api-calls",
                 "quantity":3,"eventTime":"2026-01-01T00:00:01Z"}
                """;
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));
        // 重复提交
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(jsonPath("$.result").value("DUPLICATE"));

        mvc.perform(get("/api/aggregates").param("tenantId", "tenant-api")
                        .param("metric", "api-calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].totalQuantity").value(3));

        String policy = """
                {"tenantId":"tenant-api","metric":"api-calls","period":"PT1H",
                 "limit":10,"alertThresholdRatio":0.5,"overLimitStrategy":"BLOCK"}
                """;
        mvc.perform(put("/api/quota/policies").contentType(MediaType.APPLICATION_JSON).content(policy))
                .andExpect(status().isOk());

        mvc.perform(post("/api/quota/check").param("tenantId", "tenant-api")
                        .param("metric", "api-calls").param("requested", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.limit").value(10));
    }
}
