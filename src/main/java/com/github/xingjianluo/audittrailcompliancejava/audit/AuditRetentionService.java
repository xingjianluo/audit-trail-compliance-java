package com.github.xingjianluo.audittrailcompliancejava.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保留策略：按配置保留天数清理过期审计数据。
 * <p>
 * 清理不是静默删除：先快照待删数据，删除后写入一条清理留痕事件
 * （action=AUDIT_RETENTION_CLEANUP），记录清理时的策略、截止时间点、
 * 删除数量、按结果分类的统计与被删事件指纹摘要，
 * 保证清理后仍然有效的记录可解释、历史结论可追溯。
 */
@Service
public class AuditRetentionService {

    public static final String CLEANUP_ACTION = "AUDIT_RETENTION_CLEANUP";

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final AuditEventStore store;
    private final AuditProperties properties;

    public AuditRetentionService(AuditEventStore store, AuditProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 执行一次清理，返回删除的条数。
     */
    public int cleanup() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(properties.getRetentionDays(), ChronoUnit.DAYS);

        // 1. 先快照将被删除的事件，用于生成可追溯的留痕摘要
        List<AuditEvent> expired = store.findByTimeRangeAndActor(null, cutoff, null)
                .stream()
                .filter(e -> !CLEANUP_ACTION.equals(e.getAction())) // 永不清理留痕事件本身
                .toList();
        if (expired.isEmpty()) {
            log.info("[AUDIT-RETENTION] no expired events, cutoff={} retentionDays={}",
                    cutoff, properties.getRetentionDays());
            return 0;
        }

        // 2. 按事件身份精确删除，避免误删清理期间新写入的记录
        int removed = 0;
        for (AuditEvent e : expired) {
            if (store.remove(e)) {
                removed++;
            }
        }

        // 3. 写入清理留痕事件，保证历史可追溯
        AuditEvent tombstone = buildTombstone(now, cutoff, expired, removed);
        store.save(tombstone);
        log.info("[AUDIT-RETENTION] removed={} cutoff={} retentionDays={} fingerprint={}",
                removed, cutoff, properties.getRetentionDays(), tombstone.getDetail());
        return removed;
    }

    private AuditEvent buildTombstone(Instant now, Instant cutoff,
                                      List<AuditEvent> expired, int removed) {
        Map<AuditResult, Integer> byResult = new EnumMap<>(AuditResult.class);
        Instant min = null;
        Instant max = null;
        StringBuilder fingerprintInput = new StringBuilder();
        for (AuditEvent e : expired) {
            byResult.merge(e.getResult(), 1, Integer::sum);
            if (min == null || e.getTimestamp().isBefore(min)) {
                min = e.getTimestamp();
            }
            if (max == null || e.getTimestamp().isAfter(max)) {
                max = e.getTimestamp();
            }
            fingerprintInput.append(e.getEventId()).append('|')
                    .append(e.getTimestamp()).append('|')
                    .append(e.getActor()).append('|')
                    .append(e.getAction()).append('|')
                    .append(e.getResult()).append('\n');
        }

        AuditEvent tombstone = new AuditEvent();
        tombstone.setEventId(UUID.randomUUID().toString());
        tombstone.setTimestamp(now);
        tombstone.setActor("system");
        tombstone.setAction(CLEANUP_ACTION);
        tombstone.setTarget("audit-events");
        tombstone.setResult(AuditResult.SUCCESS);
        tombstone.setDurationMs(0);
        tombstone.setDetail(String.format(
                "retentionDays=%d cutoff=%s removed=%d removedRange=[%s,%s] byResult=%s fingerprint=%s",
                properties.getRetentionDays(), cutoff, removed, min, max,
                byResult, sha256(fingerprintInput.toString())));
        return tombstone;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
