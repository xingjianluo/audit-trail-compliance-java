package com.github.xingjianluo.audittrailcompliancejava.audit.write;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计事件 ID 生成器。
 *
 * <p>格式：{@code <16位事件时间戳hex>-<16位全局单调序号hex>}，例如
 * {@code 0192f3a0b4c1d2ef-00000000000004d2}。
 *
 * <p>为什么不用“每毫秒计数器”：多线程各自读取墙钟时间戳时，同一毫秒内的取号
 * 可能交错乱序，基于时间戳回退的状态会产生重复序号。这里改用全局原子递增序号，
 * 即使事件时间戳相同（或极端情况下时钟回退导致相邻时间戳交错），eventId 依然全局唯一；
 * 存储侧排序键 (timestamp, eventId) 的确定性也不受影响。
 */
@Component
public class EventIdGenerator {

    private final AtomicLong sequence = new AtomicLong();

    public String nextId(long timestampMillis) {
        long seq = sequence.getAndIncrement();
        return String.format("%016x-%016x", timestampMillis, seq);
    }
}
