# 请求审计与合规留痕能力说明

本工程在 Spring Boot 骨架之上实现了一套**请求审计与合规留痕**能力：每一次进入系统的
接口调用（成功或失败）都会被完整、可解释地记录；审计写入与业务处理解耦；支持按时间
范围与操作者查询、游标分页，以及版本化的数据保留策略。审计库以**本地内存存储**模拟。

---

## 1. 总体结构

```
HTTP 请求
   │
   ▼
AuditFilter（@Order(1)，包裹全部对外接口）
   │  认证 / 鉴权 / 异常兜底 / 采集请求与响应 / 结果分类 / 计时
   ▼
AuditEvent（不可变记录）
   │  publish()：仅入有界队列，快速返回、绝不抛异常
   ▼
AsyncAuditEventWriter（单线程 FIFO + 重试 + 故障挂起）
   ▼
AuditEventRepository（InMemoryAuditEventRepository，本地模拟审计库）
```

查询与保留策略：

- `GET /api/audit/events`：时间范围 + 操作者 + 游标分页查询（ADMIN）。
- `GET|POST /api/audit/retention`：查看 / 调整保留策略（ADMIN）。
- `POST /api/audit/retention/purge`：立即按当前策略清理过期数据（ADMIN）。
- `GET /api/audit/retention/health`：审计写入健康度与降级信号（ADMIN）。

---

## 2. 审计事件字段模型（AuditEvent）

| 字段 | 含义 |
| --- | --- |
| `eventId` | 全局唯一 ID，`<16位事件时间戳hex>-<16位全局单调序号hex>`，定长、可排序 |
| `timestamp` | 请求进入系统的时间（epoch 毫秒），回答“什么时候” |
| `actorId` / `actorName` | 操作者标识与名称；未携带凭据记为 `anonymous`，凭据非法记为 `unauthenticated`，回答“是谁” |
| `clientIp` | 来源 IP（优先取 `X-Forwarded-For` 首段） |
| `method` / `path` | HTTP 方法与请求路径 |
| `action` | 动作类别：`CREATE` / `READ` / `UPDATE` / `DELETE` / `CONFIG_CHANGE` / `PURGE` |
| `objectType` | 对象类型：`DOCUMENT` / `AUDIT_EVENT` / `RETENTION_POLICY` / `UNKNOWN` |
| `objectId` | 操作对象：取路径变量；创建成功时从响应体解析新对象 ID |
| `outcome` | **结果类别**（见下表），回答“结果如何” |
| `errorCode` / `errorMessage` | 失败时的机器码与可读原因 |
| `httpStatus` | 最终返回给调用方的 HTTP 状态码 |
| `requestParamsMasked` | 脱敏后的 query string |
| `requestBodyMasked` | 脱敏后的请求体（JSON 或正则兜底结果，超长截断并标记 `<truncated>`） |
| `durationMs` | 请求处理耗时（毫秒），回答“耗时多久” |
| `traceId` | 单次请求追踪 ID，同时写入日志 MDC，可与审计记录互相印证 |

### 结果分类（outcome）——成功与各类失败严格可区分

| outcome | 触发条件 | HTTP 状态 |
| --- | --- | --- |
| `SUCCESS` | 2xx | 2xx |
| `AUTHN_FAILED` | 缺少凭据 / token 非法（认证失败） | 401 |
| `AUTHZ_FAILED` | 已认证但无权限（普通用户访问 `/api/audit/*`） | 403 |
| `VALIDATION_FAILED` | 请求体/参数非法（`@Valid`、400/404/405、非法游标等） | 400/404/405 |
| `BUSINESS_FAILED` | 可预期业务异常（如对象不存在 `DOCUMENT_NOT_FOUND`） | 422 |
| `SERVER_ERROR` | 未预期系统异常 | 500 |

---

## 3. 敏感信息脱敏范围

所有请求内容（query 参数与请求体）在写入审计库**之前**必须经过 `SensitiveDataMasker`，
原始敏感值绝不落库。

1. **字段名规则（整体掩码为 `***`）**，大小写不敏感：
   `password` / `passwd` / `pwd` / `oldPassword` / `newPassword` /
   `token` / `accessToken` / `refreshToken` / `authorization` /
   `secret` / `apiKey` / `appSecret` / `idCard` / `bankCard` / `cardNo` / `cvv` 等。
2. **值模式规则（部分掩码、保留可辨认结构）**，与字段名无关：
   - 手机号：`138****5678`
   - 邮箱：`a***@example.com`
   - 身份证：保留前 4 后 4，其余掩码
   - 银行卡：保留后 4 位
   - `Bearer <token>`：替换为 `Bearer ***`
3. **非 JSON 输入**（纯文本 / 表单片段）使用正则兜底；键值对中的敏感键同样被掩码。
4. 单条脱敏内容上限 4096 字符，超出截断并追加 `...<truncated>`，避免超大请求撑爆审计库。

---

## 4. 写入解耦与降级策略

业务线程只调用 `AuditEventSink.publish(event)`，与存储写入完全解耦：

- **快速返回**：publish 只做有界队列（容量 4096）入队，等待上限 100ms，
  **永不向调用方抛异常**。审计库宕机或变慢不会改变主请求的状态码与返回内容。
- **不丢、不乱、不重复**：
  - 单后台线程按 FIFO 顺序写库（单写者），eventId 全局唯一；
  - 存储侧 `putIfAbsent` 幂等防重，重复 eventId 直接拒绝；
  - 写入失败先按 `10/20/50/100/200ms` 退避重试（最多 5 次）；
  - 重试用尽后该事件成为**挂起点**：在它成功落库前不消费任何后续事件
    （队头阻塞），存储恢复后严格保序补齐，事件不丢、顺序不乱。
- **可区分、不静默的降级信号**（`GET /api/audit/retention/health`）：
  | 指标 | 含义 |
  | --- | --- |
  | `enqueued` / `written` | 入队 / 成功写入计数 |
  | `retried` | 发生过失败重试（多为瞬时抖动后自愈） |
  | `slowWrites` | 单次存储调用 ≥ 100ms（性能降级预警，区别于失败） |
  | `failed` / `deadLetter` | 重试用尽、事件被挂起（存储不可用） |
  | `queueRejected` | 队列积压触发背压拒绝（写入长期跟不上），同时打印 ERROR 日志 |
  | `queueSize` | 当前队列深度 |
  | `parkedEventIds` | 最近被挂起的事件 ID，用于解释降级原因 |
  | `degraded` | 任一非健康计数大于 0 即为降级态 |
- 关闭时（`@PreDestroy`）优雅停止，最多等待 2s 刷盘，尽量不丢残留事件。

---

## 5. 查询与保留策略

### 查询

- 过滤条件：`startTime` / `endTime`（epoch 毫秒，闭区间）、`actorId`。
- **游标分页（keyset pagination）**：排序键固定为 `(timestamp, sequence)`，
  游标为 `base64url("timestamp|sequence")`，`pageSize` 1~200（默认 20）。
  相比 offset 分页，数据追加或清理后不会翻页错乱、不会重复记录；
  在固定时间区间上逐页结果可复现。

### 保留策略

- 策略是**带版本号的不可变记录**：默认 v1 = 保留 90 天。
  调整保留时长只会**追加新版本**（含创建人、时间、备注），历史版本永不覆盖，
  因而任何一次历史清理都能追溯“当时依据的是哪个版本”。
- 清理只删除 `timestamp` **严格早于** `now - retentionMillis` 的记录；
  清理动作本身产生一条 `action=PURGE` 的审计事件，记录执行人、依据策略版本、
  截止时间、删除条数与剩余条数。该事件产生于截止时间之后，永远不会被自身或后续清理误删；
  仍在保留期内的记录不受影响，历史结论保持可解释。

---

## 6. 本地验证

### 运行测试

```bash
mvn clean test
```

测试覆盖（测试日志均打印“输入事件 + 最终判定依据”）：

| 测试类 | 覆盖点 |
| --- | --- |
| `SensitiveDataMaskerTest` | 敏感字段整体掩码、手机/邮箱/身份证/银行卡/Bearer 部分掩码、非 JSON 兜底 |
| `InMemoryAuditEventRepositoryTest` | 16 线程并发写入不丢不重、确定性排序、按时间/操作者查询、只清过期数据 |
| `AsyncAuditEventWriterTest` | 并发发布全量保序落库；存储故障期业务不受影响、恢复后不丢不乱序、降级指标可区分；慢写独立信号 |
| `AuditTrailEndToEndTest` | 真实 HTTP 调用：成功 + 认证失败 + 鉴权失败 + 参数非法 + 业务异常五类结果；脱敏落库；操作者/时间查询；游标分页复现 |
| `RetentionServiceTest` | 策略版本化、只删过期、清理自身留痕、重复清理安全 |
| `RetentionEndToEndTest` | 管理接口鉴权、策略调整/历史/清理、健康检查字段 |

### 手工验证

```bash
mvn spring-boot:run        # server.port=0 时查看启动日志中的实际端口
```

演示用 token（见 `AuthTokenTable`）：

- `Bearer token-user`：普通用户 alice（`u-1001`）
- `Bearer token-admin`：管理员 admin（`u-0001`）
- `Bearer bad-token`：非法 token（制造 401）

```bash
# 成功创建（请求体故意带 password，审计中只能看到 ***）
curl -s -X POST localhost:$PORT/api/documents \
  -H 'Authorization: Bearer token-user' -H 'Content-Type: application/json' \
  -d '{"title":"t1","content":"hello","password":"Hunter2Secret"}'

# 认证失败 / 鉴权失败 / 参数非法 / 业务异常
curl -i localhost:$PORT/api/documents
curl -i -H 'Authorization: Bearer bad-token' localhost:$PORT/api/documents
curl -i -H 'Authorization: Bearer token-user' localhost:$PORT/api/audit/retention
curl -i -X POST -H 'Authorization: Bearer token-user' -H 'Content-Type: application/json' \
  -d '{"title":""}' localhost:$PORT/api/documents
curl -i -H 'Authorization: Bearer token-user' localhost:$PORT/api/documents/no-such-id

# 查询审计事件（管理员）：时间范围 + 操作者 + 分页
curl -s -H 'Authorization: Bearer token-admin' \
  "localhost:$PORT/api/audit/events?actorId=u-1001&pageSize=2"
curl -s -H 'Authorization: Bearer token-admin' \
  "localhost:$PORT/api/audit/events?startTime=<epochMs>&endTime=<epochMs>&cursor=<nextCursor>"

# 健康度 / 保留策略
curl -s -H 'Authorization: Bearer token-admin' localhost:$PORT/api/audit/retention/health
curl -s -H 'Authorization: Bearer token-admin' localhost:$PORT/api/audit/retention/history
curl -s -X POST -H 'Authorization: Bearer token-admin' \
  "localhost:$PORT/api/audit/retention?retentionMillis=2592000000&remark=30d"
curl -s -X POST -H 'Authorization: Bearer token-admin' localhost:$PORT/api/audit/retention/purge
```

### 模拟审计库故障 / 变慢

`InMemoryAuditEventRepository` 暴露测试开关 `setFault(true)` 与 `setSlowMillis(n)`，
`AsyncAuditEventWriterTest` 已基于它们验证：故障窗口内业务请求照常返回、
`failed/retried` 等指标上升，恢复后事件严格按序补齐且一条不丢。
