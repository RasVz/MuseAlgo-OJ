package top.everblue.oj.common.config;

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
}
