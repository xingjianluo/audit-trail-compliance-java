# 请求审计与合规留痕

本工程在 Spring Boot 骨架上实现了一套请求审计与合规留痕能力：所有对外接口的调用
（无论成功或失败）都会被完整、可解释地记录到本地模拟审计库（内存实现），
审计写入与业务处理解耦，支持脱敏、失败分类、降级信号、分页查询与保留策略。

## 审计事件字段模型

每次接口调用生成一条 `AuditEvent`：

| 字段 | 含义 |
| --- | --- |
| `eventId` | 事件唯一 ID（UUID） |
| `sequence` | 存储分配的单调递增序号，用于稳定排序 |
| `timestamp` | 事件时间（UTC Instant） |
| `actor` | 操作者，取自请求头 `X-User`，缺省为 `anonymous` |
| `action` | 动作，形如 `POST /api/demo/transfer` |
| `target` | 作用对象（请求 URI） |
| `result` | 结果分类，见下表 |
| `durationMs` | 请求处理耗时（毫秒） |
| `clientIp` | 客户端 IP（优先 `X-Forwarded-For`） |
| `httpMethod` / `path` | HTTP 方法与路径 |
| `detail` | 脱敏后的请求参数 / 异常摘要 |

### 结果分类（`AuditResult`）

成功与失败可区分，失败进一步细分为可区分的类别，不会笼统记为一次失败：

| 类别 | 触发场景 |
| --- | --- |
| `SUCCESS` | 2xx/3xx 响应 |
| `AUTHENTICATION_FAILED` | 认证失败（401 / `AuthenticationException`） |
| `AUTHORIZATION_FAILED` | 鉴权失败（403 / `AuthorizationException`） |
| `VALIDATION_FAILED` | 参数非法（400 / 参数校验异常 / `IllegalArgumentException`） |
| `BUSINESS_ERROR` | 业务异常（422 / `BusinessException`） |
| `SYSTEM_ERROR` | 其他系统异常（5xx） |

分类由 `AuditExceptionClassifier` 完成；被 `@RestControllerAdvice` 处理的异常
会把分类结果写入请求属性，审计拦截器优先采用，其次按异常类型、最后按响应状态码推断。

## 脱敏范围

`SensitiveDataMasker` 在审计落库前对 `detail` 中的敏感字段脱敏（替换为 `******`），
原值绝不落库。覆盖 query string（`k=v`）与 JSON 文本（`"k":"v"`）两种形态，
敏感字段名（大小写不敏感）包括：

`password`、`passwd`、`pwd`、`secret`、`token`、`accesstoken`、`authorization`、
`credential`、`idcard`、`idnumber`、`phone`、`mobile`、`bankcard`、`cardno`

## 写入解耦与降级策略

- `AuditInterceptor` 只负责构造事件并调用 `AuditEventPublisher.publish()`，
  主请求线程仅做**非阻塞入队**，由独立后台线程批量写入审计库；
  审计存储不可用或写入变慢都不会改变主请求的结果。
- 降级信号（不静默吞掉）：
  - 存储写失败 / 队列满 / 关闭中发布 → 事件进入有界降级缓冲区，
    状态为 `DEGRADED_BUFFERED`；缓冲区也满则为 `DEGRADED_DROPPED`；
  - 每次降级都打印 `[AUDIT-DEGRADED]` 警告日志（含状态、原因、事件摘要），
    并累计降级计数（`getDegradedCount()`），可据此监控告警。
- 高并发保障：`ConcurrentLinkedDeque` + 单调序号，写入不丢失、不错乱、
  不重复记账；查询按 `(timestamp, sequence)` 排序，分页稳定可复现。

## 查询与保留策略

- 查询接口：`GET /api/audit/events?from=<ISO时间>&to=<ISO时间>&actor=<操作者>&page=0&size=20`，
  按时间范围与操作者过滤，分页结果稳定可复现。
- 保留策略：`AuditRetentionService.cleanup()` 按 `audit.retention-days`（默认 30 天）
  清理过期数据。清理不是静默删除：
  1. 先快照待删事件；
  2. 按事件身份精确删除（不误删清理期间新写入的记录）；
  3. 写入一条 `AUDIT_RETENTION_CLEANUP` 留痕事件，记录清理时的策略值、
     截止时间、删除数量、按结果分类的统计、被删事件的时间范围与 SHA-256 指纹；
  4. 留痕事件本身永不被清理，策略调整前后的历史结论均可追溯。

## 配置项（`application.properties`）

```properties
audit.queue-capacity=10000   # 审计写入队列容量
audit.retention-days=30      # 审计数据保留天数
```

## 本地验证

```bash
# 全量测试（13 个用例）
mvn test

# 单独验证某一方面
mvn test -Dtest=InMemoryAuditEventStoreConcurrencyTest   # 并发写入不丢不重
mvn test -Dtest=AuditEventPublisherDegradationTest       # 存储不可用时的降级信号
mvn test -Dtest=SensitiveDataMaskerTest                  # 敏感信息脱敏
mvn test -Dtest=AuditExceptionClassifierTest             # 失败分类
mvn test -Dtest=AuditRetentionServiceTest                # 保留策略与清理留痕
mvn test -Dtest=AuditTrailIntegrationTest                # 端到端留痕
```

测试日志中会打印每条用例的「输入事件」与「判定依据」，可直接核对断言理由。

手工验证：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

curl -H 'X-User: alice' 'http://localhost:8080/api/demo/ok?name=bob'
curl -X POST 'http://localhost:8080/api/demo/transfer?account=a1&amount=100&password=s3cret'
curl -H 'X-User: carol' 'http://localhost:8080/api/demo/need-auth'   # 401
curl 'http://localhost:8080/api/audit/events?actor=alice&page=0&size=10'
```

随后在查询接口返回中可看到：`password` 已脱敏为 `******`，各类失败分别归入
`AUTHENTICATION_FAILED` / `AUTHORIZATION_FAILED` / `VALIDATION_FAILED` /
`BUSINESS_ERROR` / `SYSTEM_ERROR`。
