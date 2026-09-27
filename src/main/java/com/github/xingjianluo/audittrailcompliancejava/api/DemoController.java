package com.github.xingjianluo.audittrailcompliancejava.api;

import com.github.xingjianluo.audittrailcompliancejava.web.AuthenticationException;
import com.github.xingjianluo.audittrailcompliancejava.web.AuthorizationException;
import com.github.xingjianluo.audittrailcompliancejava.web.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 演示业务接口：覆盖成功与各类失败场景，验证审计留痕。 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    @GetMapping("/ok")
    public Map<String, Object> ok(@RequestParam String name) {
        return Map.of("message", "hello " + name);
    }

    @PostMapping("/transfer")
    public Map<String, Object> transfer(@RequestParam String account,
                                        @RequestParam long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (amount > 1_000_000) {
            throw new BusinessException("amount exceeds limit");
        }
        return Map.of("transferred", amount, "account", account);
    }

    @GetMapping("/need-auth")
    public Map<String, Object> needAuth() {
        throw new AuthenticationException("missing or invalid credential");
    }

    @GetMapping("/need-admin")
    public Map<String, Object> needAdmin() {
        throw new AuthorizationException("admin role required");
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new IllegalStateException("unexpected system failure");
    }
}
