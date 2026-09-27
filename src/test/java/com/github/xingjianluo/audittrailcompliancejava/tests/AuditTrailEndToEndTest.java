package com.github.xingjianluo.audittrailcompliancejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventPage;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import com.github.xingjianluo.audittrailcompliancejava.audit.web.AuditQueryService;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventQuery;
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

import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：真实 HTTP 调用 -> 过滤器留痕 -> 本地审计库 -> 查询。
 * 覆盖成功与四类失败的可区分分类、敏感字段脱敏、操作者/时间范围查询与游标分页复现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditTrailEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailEndToEndTest.class);

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AuditEventRepository repository;
    @Autowired
    AuditQueryService queryService;

    private final ObjectMapper om = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json");
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    @Test
    void everyCall_isAuditedWithDistinctOutcomes_andMasking() {
        long t0 = System.currentTimeMillis();

        // 1) 成功：创建文档（请求体带密码字段，必须脱敏）
        ResponseEntity<String> created = rest.exchange(url("/api/documents"), HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"t1\",\"content\":\"c\",\"password\":\"Hunter2Secret\"}",
                        auth("token-user")), String.class);
        assertEquals(201, created.getStatusCode().value());

        // 2) 认证失败：无 token
        ResponseEntity<String> noToken = rest.exchange(url("/api/documents"), HttpMethod.GET,
                new HttpEntity<>(auth(null)), String.class);
        // 3) 认证失败：非法 token
        ResponseEntity<String> badToken = rest.exchange(url("/api/documents"), HttpMethod.GET,
                new HttpEntity<>(auth("bad-token")), String.class);
        // 4) 鉴权失败：普通用户访问 admin 接口
        ResponseEntity<String> forbidden = rest.exchange(url("/api/audit/retention"), HttpMethod.GET,
                new HttpEntity<>(auth("token-user")), String.class);
        // 5) 参数非法：title 为空
        ResponseEntity<String> invalid = rest.exchange(url("/api/documents"), HttpMethod.POST,
                new HttpEntity<>("{\"title\":\"\",\"content\":\"c\"}", auth("token-user")), String.class);
        // 6) 业务异常：查不存在的对象
        ResponseEntity<String> bizErr = rest.exchange(url("/api/documents/no-such-id"), HttpMethod.GET,
                new HttpEntity<>(auth("token-user")), String.class);
        // 7) 成功：列表
        ResponseEntity<String> okList = rest.exchange(url("/api/documents"), HttpMethod.GET,
                new HttpEntity<>(auth("token-user")), String.class);

        log.info("[HTTP结果] created={}, noToken={}, badToken={}, forbidden={}, invalid={}, bizErr={}, list={}",
                created.getStatusCode(), noToken.getStatusCode(), badToken.getStatusCode(),
                forbidden.getStatusCode(), invalid.getStatusCode(), bizErr.getStatusCode(),
                okList.getStatusCode());

        assertEquals(401, noToken.getStatusCode().value());
        assertEquals(401, badToken.getStatusCode().value());
        assertEquals(403, forbidden.getStatusCode().value());
        assertEquals(400, invalid.getStatusCode().value());
        assertEquals(422, bizErr.getStatusCode().value());
        assertEquals(200, okList.getStatusCode().value());

        // 等待 7 条审计事件全部异步落库
        long t1 = System.currentTimeMillis();
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(repository.count(t0, t1 + 5000, null) >= 7));

        List<AuditEvent> events = repository.search(t0, t1 + 5000, null, null, null, 500);

        long success = events.stream().filter(e -> e.outcome().name().equals("SUCCESS")).count();
        long authn = events.stream().filter(e -> e.outcome().name().equals("AUTHN_FAILED")).count();
        long authz = countOutcome(events, "AUTHZ_FAILED");
        long validation = countOutcome(events, "VALIDATION_FAILED");
        long business = countOutcome(events, "BUSINESS_FAILED");

        log.info("[审计分类] 输入 7 次调用, 最终判定依据: SUCCESS={}, AUTHN_FAILED={}, AUTHZ_FAILED={}, VALIDATION_FAILED={}, BUSINESS_FAILED={}",
                success, authn, authz, validation, business);

        assertTrue(success >= 2, "创建+列表至少 2 条成功");
        assertEquals(2, authn, "无token/坏token 必须都归入认证失败");
        assertEquals(1, authz, "普通用户访问管理接口必须归入鉴权失败");
        assertEquals(1, validation, "参数非法必须单独归类");
        assertEquals(1, business, "业务异常必须单独归类");

        // 可解释性断言：谁/何时/做了什么/对象/耗时
        AuditEvent createEvent = events.stream()
                .filter(e -> "CREATE".equals(e.action()) && e.outcome().name().equals("SUCCESS"))
                .findFirst().orElseThrow();
        log.info("[字段完整性] 创建事件: eventId={}, actor={}, at={}, action={}, objectType={}, objectId={}, duration={}ms, traceId={}",
                createEvent.eventId(), createEvent.actorId(), createEvent.timestamp(),
                createEvent.action(), createEvent.objectType(), createEvent.objectId(),
                createEvent.durationMs(), createEvent.traceId());
        assertEquals("u-1001", createEvent.actorId());
        assertEquals("DOCUMENT", createEvent.objectType());
        assertNotNull(createEvent.objectId(), "成功创建必须解析到对象 id");
        assertTrue(createEvent.durationMs() >= 0);
        assertNotNull(createEvent.traceId());

        // 脱敏断言：密码不得原样出现在审计记录中
        log.info("[脱敏落库] 创建事件 requestBodyMasked={}", createEvent.requestBodyMasked());
        assertFalse(createEvent.requestBodyMasked().contains("Hunter2Secret"), "敏感信息原样落库");
        assertTrue(createEvent.requestBodyMasked().contains("\"password\":\"***\""));

        // 认证失败事件的操作者必须可区分：未携带凭据=anonymous，凭据非法=unauthenticated
        List<AuditEvent> authnEvents = events.stream()
                .filter(e -> e.outcome().name().equals("AUTHN_FAILED")).toList();
        List<String> authnActors = authnEvents.stream().map(AuditEvent::actorId).sorted().toList();
        log.info("[失败可解释] 认证失败事件 actor 集合: {} (期望 [anonymous, unauthenticated])", authnActors);
        assertEquals(List.of("anonymous", "unauthenticated"), authnActors,
                "未携带凭据与凭据非法必须可以区分");
        AuditEvent authnEvent = authnEvents.get(0);
        log.info("[失败可解释] 认证失败事件样例: actor={}, code={}, message={}, httpStatus={}",
                authnEvent.actorId(), authnEvent.errorCode(), authnEvent.errorMessage(),
                authnEvent.httpStatus());
        assertEquals("AUTHN_FAILED", authnEvent.errorCode());
        assertEquals(401, authnEvent.httpStatus());

        AuditEvent bizEvent = events.stream()
                .filter(e -> e.outcome().name().equals("BUSINESS_FAILED")).findFirst().orElseThrow();
        log.info("[失败可解释] 业务异常事件: code={}, message={}, objectId={}",
                bizEvent.errorCode(), bizEvent.errorMessage(), bizEvent.objectId());
        assertEquals("DOCUMENT_NOT_FOUND", bizEvent.errorCode());
        assertEquals("no-such-id", bizEvent.objectId());
    }

    @Test
    void query_byActorAndTimeRange_isStableAndReproducibleWithCursor() {
        long t0 = System.currentTimeMillis();
        // alice 连续 3 次成功调用
        for (int i = 0; i < 3; i++) {
            rest.exchange(url("/api/documents"), HttpMethod.GET,
                    new HttpEntity<>("{}", auth("token-user")), String.class);
        }
        // 直接走仓储层等待并冻结时间上界，避免后续“查询审计接口本身又产生新事件”干扰复现
        long t1 = System.currentTimeMillis() + 1000;
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(repository.count(t0, t1, "u-1001") >= 3));

        // 操作者过滤
        long aliceCount = repository.count(t0, t1, "u-1001");
        long adminCount = repository.count(t0, t1, "u-0001");
        log.info("[操作者查询] 输入 actor=alice/admin, 最终判定依据 alice={} 条, admin={} 条",
                aliceCount, adminCount);
        assertTrue(aliceCount >= 3);
        assertEquals(0, adminCount, "管理员在本用例没有操作");

        // 基准快照：固定 [t0,t1] 区间一次性取全
        List<String> allIds = repository.search(t0, t1, "u-1001", null, null, 1000)
                .stream().map(AuditEvent::eventId).toList();

        // 游标分页复现：pageSize=2 逐页拼接，必须与基准快照逐一致
        List<String> paged = new ArrayList<>();
        String cursor = null;
        int loops = 0;
        while (true) {
            AuditEventPage page = queryService.query(
                    new AuditEventQuery(t0, t1, "u-1001", cursor, 2));
            page.items().forEach(e -> paged.add(e.eventId()));
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
            if (++loops > 50) {
                throw new AssertionError("分页没有终止");
            }
        }

        log.info("[分页复现] 输入 pageSize=2 游标翻页, 最终判定依据: 分页拼接={} 条, 全量={} 条, 一致={}, 无重复={}",
                paged.size(), allIds.size(), paged.equals(allIds),
                paged.stream().distinct().count() == paged.size());

        assertEquals(allIds, paged, "游标分页结果必须与固定区间快照一致且可复现");
        assertEquals(paged.stream().distinct().count(), paged.size(), "分页不能重复记账");
        assertTrue(paged.size() >= 3);
    }

    @Test
    void adminCanReadAuditEventsViaHttp() throws Exception {
        ResponseEntity<String> resp = rest.exchange(url("/api/audit/events?pageSize=5"),
                HttpMethod.GET, new HttpEntity<>(auth("token-admin")), String.class);
        assertEquals(200, resp.getStatusCode().value());
        JsonNode body = om.readTree(resp.getBody());
        log.info("[HTTP查询] /api/audit/events 最终判定依据: totalMatched={}, 返回={} 条, hasMore={}",
                body.get("totalMatched").asLong(), body.get("items").size(),
                body.get("hasMore").asBoolean());
        assertTrue(body.get("items").size() <= 5);
        assertNull(body.get("items").get(0) == null ? null : body.get("items").get(0).get("password"));
    }

    private static long countOutcome(List<AuditEvent> events, String name) {
        return events.stream().filter(e -> e.outcome().name().equals(name)).count();
    }
}
