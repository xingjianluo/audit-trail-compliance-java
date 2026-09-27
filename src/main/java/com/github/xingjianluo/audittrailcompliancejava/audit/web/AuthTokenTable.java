package com.github.xingjianluo.audittrailcompliancejava.audit.web;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 极简本地 token 表，模拟认证体系（骨架项目无需真正的安全框架）。
 *
 * <p>约定：
 * <ul>
 *   <li>无 Authorization 头 -> 匿名（仅允许登录等公开接口；本演示所有接口都要求登录）；</li>
 *   <li>{@code Bearer bad-token} -> 故意制造的非法 token，归入 AUTHN_FAILED；</li>
 *   <li>{@code Bearer token-user} -> 普通用户 alice；</li>
 *   <li>{@code Bearer token-admin} -> 管理员 admin。</li>
 * </ul>
 */
@Component
public class AuthTokenTable {

    public record Principal(String userId, String userName, String role) {
    }

    private static final Map<String, Principal> TOKENS = Map.of(
            "token-user", new Principal("u-1001", "alice", "USER"),
            "token-admin", new Principal("u-0001", "admin", "ADMIN")
    );

    /**
     * @param rawToken Authorization 头去掉 Bearer 前缀后的部分；null 表示匿名
     * @return 认证主体；空 Optional 表示 token 非法（认证失败，区别于匿名）
     */
    public Optional<Principal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        if ("bad-token".equals(rawToken)) {
            return Optional.empty();
        }
        return Optional.ofNullable(TOKENS.get(rawToken));
    }
}
