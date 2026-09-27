package com.github.xingjianluo.audittrailcompliancejava.audit.web;

/**
 * 当前请求操作者。认证成功后由 AuditFilter 写入 ThreadLocal，供业务层记录对象级信息。
 */
public record ActorContext(String userId, String userName, String role, String clientIp) {

    private static final ThreadLocal<ActorContext> HOLDER = new ThreadLocal<>();

    public static void set(ActorContext ctx) {
        HOLDER.set(ctx);
    }

    public static ActorContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
