package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEventPublisher;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditEventStore;
import com.github.xingjianluo.audittrailcompliancejava.audit.AuditResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 端到端：全部对外接口的成功与失败都被完整留痕，敏感信息脱敏后落库。 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditEventStore store;

    @Autowired
    private AuditEventPublisher publisher;

    @Test
    void everyRequestIsAuditedWithDistinctResultCategories() throws Exception {
        long before = store.count();

        mockMvc.perform(get("/api/demo/ok").param("name", "alice").header("X-User", "alice"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        mockMvc.perform(post("/api/demo/transfer")
                        .param("account", "acc-1").param("amount", "100")
                        .param("password", "topsecret").header("X-User", "bob"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        mockMvc.perform(get("/api/demo/need-auth").header("X-User", "carol"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401));
        mockMvc.perform(get("/api/demo/need-admin").header("X-User", "dave"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(403));
        mockMvc.perform(post("/api/demo/transfer").param("account", "acc-2").param("amount", "-5"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(400));
        mockMvc.perform(post("/api/demo/transfer").param("account", "acc-3").param("amount", "99999999"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(422));
        mockMvc.perform(get("/api/demo/boom"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(500));

        publisher.awaitDrain(5000);
        long deadline = System.currentTimeMillis() + 5000;
        while (store.count() < before + 7 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        List<AuditEvent> events = store.findByTimeRangeAndActor(null, null, null);
        log.info("输入事件: 7 次接口调用(成功x2/认证失败/鉴权失败/参数非法/业务异常/系统异常)");
        log.info("判定依据: 审计记录总数={}, 应新增 7 条", events.size());
        assertThat(events.size()).isEqualTo(before + 7);

        assertResult(events, "/api/demo/ok", AuditResult.SUCCESS);
        assertResult(events, "/api/demo/need-auth", AuditResult.AUTHENTICATION_FAILED);
        assertResult(events, "/api/demo/need-admin", AuditResult.AUTHORIZATION_FAILED);
        assertResult(events, "/api/demo/boom", AuditResult.SYSTEM_ERROR);

        List<AuditEvent> transfers = events.stream()
                .filter(e -> "/api/demo/transfer".equals(e.getPath())).toList();
        log.info("判定依据: transfer 的三种结果={} 应包含 SUCCESS/VALIDATION_FAILED/BUSINESS_ERROR",
                transfers.stream().map(AuditEvent::getResult).toList());
        assertThat(transfers.stream().map(AuditEvent::getResult))
                .containsExactlyInAnyOrder(AuditResult.SUCCESS,
                        AuditResult.VALIDATION_FAILED, AuditResult.BUSINESS_ERROR);

        // 谁、何时、做了什么、针对谁、耗时：字段完整
        AuditEvent ok = events.stream().filter(e -> "/api/demo/ok".equals(e.getPath())).findFirst().orElseThrow();
        log.info("判定依据: ok 事件 actor={} action={} target={} durationMs={} 字段应完整",
                ok.getActor(), ok.getAction(), ok.getTarget(), ok.getDurationMs());
        assertThat(ok.getActor()).isEqualTo("alice");
        assertThat(ok.getAction()).isEqualTo("GET /api/demo/ok");
        assertThat(ok.getTarget()).isEqualTo("/api/demo/ok");
        assertThat(ok.getTimestamp()).isNotNull();
        assertThat(ok.getDurationMs()).isGreaterThanOrEqualTo(0);

        // 敏感信息脱敏：password 不能原样落库
        AuditEvent transfer = transfers.stream()
                .filter(e -> e.getResult() == AuditResult.SUCCESS).findFirst().orElseThrow();
        log.info("判定依据: transfer 事件 detail={} 中 password 应被脱敏", transfer.getDetail());
        assertThat(transfer.getDetail()).doesNotContain("topsecret");
        assertThat(transfer.getDetail()).contains("password=******");
    }

    private void assertResult(List<AuditEvent> events, String path, AuditResult expected) {
        AuditResult actual = events.stream()
                .filter(e -> path.equals(e.getPath()))
                .map(AuditEvent::getResult)
                .findFirst().orElse(null);
        log.info("判定依据: path={} 期望结果={}, 实际结果={}", path, expected, actual);
        assertThat(actual).isEqualTo(expected);
    }
}
