package com.github.xingjianluo.audittrailcompliancejava.audit.web;

import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEvent;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventPage;
import com.github.xingjianluo.audittrailcompliancejava.audit.model.AuditEventQuery;
import com.github.xingjianluo.audittrailcompliancejava.audit.storage.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 审计查询服务。
 *
 * <p>分页为游标式（keyset pagination），避免基于 offset 在数据追加/清理后翻页错乱：
 * <ul>
 *   <li>排序键固定为 (timestamp, sequence)，sequence 是 eventId 中全局唯一且单调的序号；</li>
 *   <li>游标内容是上一页最后一条记录的排序键，编码为 base64url；</li>
 *   <li>固定时间范围 + 固定过滤条件下，逐页结果可复现（清理只影响已过期数据，
 *       查询有效区间时结论不变）。</li>
 * </ul>
 */
@Service
public class AuditQueryService {

    static final int MIN_PAGE_SIZE = 1;
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public AuditEventPage query(AuditEventQuery q) {
        int size = normalizePageSize(q.pageSize());
        Long afterTs = null;
        Long afterSeq = null;
        if (q.cursor() != null && !q.cursor().isBlank()) {
            long[] decoded = decodeCursor(q.cursor());
            afterTs = decoded[0];
            afterSeq = decoded[1];
        }

        long total = repository.count(q.startTime(), q.endTime(), q.actorId());

        // 多取 1 条判断是否有下一页
        List<AuditEvent> rows = repository.search(q.startTime(), q.endTime(), q.actorId(),
                afterTs, afterSeq, size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = rows.subList(0, size);
        }
        String nextCursor = hasMore && !rows.isEmpty()
                ? encodeCursor(rows.get(rows.size() - 1).timestamp(),
                        rows.get(rows.size() - 1).eventId())
                : null;
        return new AuditEventPage(List.copyOf(rows), nextCursor, hasMore, total);
    }

    static int normalizePageSize(int requested) {
        if (requested < MIN_PAGE_SIZE) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /** 游标编码：base64url("timestamp|sequence")。 */
    public static String encodeCursor(long timestamp, String eventId) {
        String raw = timestamp + "|" + sequenceOf(eventId);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 游标解码为 [timestamp, sequence]，非法游标抛参数异常。 */
    public static long[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf('|');
            if (sep <= 0 || sep == raw.length() - 1) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new long[]{Long.parseLong(raw.substring(0, sep)),
                    Long.parseUnsignedLong(raw.substring(sep + 1))};
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("非法的分页游标: " + cursor);
        }
    }

    /** 从 eventId（&lt;16hex时间戳&gt;-&lt;16hex全局序号&gt;）中解析序号部分。 */
    static long sequenceOf(String eventId) {
        int sep = eventId.indexOf('-');
        return Long.parseUnsignedLong(eventId.substring(sep + 1), 16);
    }
}
