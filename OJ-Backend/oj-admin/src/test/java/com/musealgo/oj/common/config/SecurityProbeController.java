package com.musealgo.oj.common.config;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SecurityProbeController {

    @GetMapping("/api/probe/authenticated")
    public String authenticated() {
        return "ok";
    }

    @GetMapping("/api/probe/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "admin";
    }

    // Mirrors the real actuator probe paths so the WebMvcTest slice can assert the security matcher
    // without booting actuator. Security decides before the dispatcher, so a stub at the same path
    // exercises exactly the rule under test.
    @GetMapping({"/actuator/health/liveness", "/actuator/health/readiness"})
    public String probe() {
        return "probe-ok";
    }
}
