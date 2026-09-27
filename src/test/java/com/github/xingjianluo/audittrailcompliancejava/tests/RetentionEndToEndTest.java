package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留策略与健康检查的 HTTP 端到端测试（含鉴权）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetentionEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(RetentionEndToEndTest.class);

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    private final ObjectMapper om = new ObjectMapper();

    private String url(String p) {
        return "http://localhost:" + port + p;
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    @Test
    void retentionEndpointsRequireAdmin_andHealthExposesDegradationSignals() throws Exception {
        // 普通用户访问保留策略 -> 403 鉴权失败
        ResponseEntity<String> forbidden = rest.exchange(url("/api/audit/retention"),
                HttpMethod.GET, new HttpEntity<>(headers("token-user")), String.class);
        log.info("[策略鉴权] 输入 USER 访问 /api/audit/retention, 最终判定依据 status={}",
                forbidden.getStatusCode().value());
        assertEquals(403, forbidden.getStatusCode().value());

        // 匿名 -> 401
        ResponseEntity<String> anonymous = rest.exchange(url("/api/audit/retention"),
                HttpMethod.GET, new HttpEntity<>(headers(null)), String.class);
        assertEquals(401, anonymous.getStatusCode().value());

        // 管理员读取当前策略与历史
        ResponseEntity<String> current = rest.exchange(url("/api/audit/retention"),
                HttpMethod.GET, new HttpEntity<>(headers("token-admin")), String.class);
        assertEquals(200, current.getStatusCode().value());
        JsonNode policy = om.readTree(current.getBody());
        log.info("[读取策略] 最终判定依据: version={}, retentionMillis={}",
                policy.get("version").asInt(), policy.get("retentionMillis").asLong());
        assertEquals(1, policy.get("version").asInt());

        // 调整策略（缩到 1 毫秒，使下一次 purge 把所有历史记录都视为过期，验证清理链路）
        long oneMs = 1L;
        ResponseEntity<String> adjusted = rest.exchange(
                url("/api/audit/retention?retentionMillis=" + oneMs + "&remark=e2e-test"),
                HttpMethod.POST, new HttpEntity<>(headers("token-admin")), String.class);
        assertEquals(200, adjusted.getStatusCode().value());
        JsonNode v2 = om.readTree(adjusted.getBody());
        log.info("[调整策略] 最终判定依据: newVersion={}, retentionMillis={}",
                v2.get("version").asInt(), v2.get("retentionMillis").asLong());
        assertEquals(2, v2.get("version").asInt());

        // 历史可追溯
        ResponseEntity<String> history = rest.exchange(url("/api/audit/retention/history"),
                HttpMethod.GET, new HttpEntity<>(headers("token-admin")), String.class);
        JsonNode hist = om.readTree(history.getBody());
        log.info("[策略历史] 最终判定依据: 版本数={}, v1仍存在={}",
                hist.size(), hist.get(0).get("version").asInt() == 1);
        assertTrue(hist.size() >= 2);

        // 执行清理
        ResponseEntity<String> purge = rest.exchange(url("/api/audit/retention/purge"),
                HttpMethod.POST, new HttpEntity<>(headers("token-admin")), String.class);
        assertEquals(200, purge.getStatusCode().value());
        JsonNode purgeBody = om.readTree(purge.getBody());
        log.info("[执行清理] 最终判定依据: policyVersion={}, purged={}, remaining={}",
                purgeBody.get("policyVersion").asInt(), purgeBody.get("purgedCount").asLong(),
                purgeBody.get("remainingCount").asLong());
        assertEquals(2, purgeBody.get("policyVersion").asInt());

        // 健康检查：降级信号字段齐全且可区分
        ResponseEntity<String> health = rest.exchange(url("/api/audit/retention/health"),
                HttpMethod.GET, new HttpEntity<>(headers("token-admin")), String.class);
        JsonNode hb = om.readTree(health.getBody());
        log.info("[健康检查] 最终判定依据: enqueued={}, written={}, failed={}, queueRejected={}, slowWrites={}, degraded={}",
                hb.get("enqueued").asLong(), hb.get("written").asLong(), hb.get("failed").asLong(),
                hb.get("queueRejected").asLong(), hb.get("slowWrites").asLong(),
                hb.get("degraded").asBoolean());
        assertTrue(hb.has("failed") && hb.has("queueRejected")
                && hb.has("slowWrites") && hb.has("deadLetter"));
    }
}
