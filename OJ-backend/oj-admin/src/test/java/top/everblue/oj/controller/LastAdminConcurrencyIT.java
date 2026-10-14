package top.everblue.oj.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.service.UserService;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 并发验证：两个启用管理员同时互相降级时，
 * 事务内的 SELECT ... FOR UPDATE 必须串行化校验，保证系统至少保留一个管理员。
 * 依赖本机 MySQL 与已授权环境变量 OJ_DB_PASS，不参与纯单测套件（以 *IT 结尾）。
 */
@SpringBootTest
class LastAdminConcurrencyIT {

    private static final String REAL_ADMIN_USERNAME = "admin";
    private static final String TEMP_PREFIX = "codex_lock_";

    @Autowired
    private AdminUserController adminUserController;
    @Autowired
    private UserService userService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long realAdminId;
    private Long tempAId;
    private Long tempBId;

    @AfterEach
    void cleanup() {
        try {
            if (realAdminId != null) {
                User restore = new User();
                restore.setId(realAdminId);
                restore.setRole((byte) 3);
                restore.setIsBanned((byte) 0);
                userService.updateById(restore);
            }
        } finally {
            if (tempAId != null) {
                userService.removeById(tempAId);
            }
            if (tempBId != null) {
                userService.removeById(tempBId);
            }
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void concurrentMutualDemotionKeepsAtLeastOneAdmin() throws Exception {
        // 清理上次异常中断可能残留的临时用户，保证场景可控
        userService.lambdaUpdate().likeRight(User::getUsername, TEMP_PREFIX).remove();

        User realAdmin = userService.lambdaQuery()
                .eq(User::getUsername, REAL_ADMIN_USERNAME)
                .one();
        assertNotNull(realAdmin, "测试依赖真实 admin 账号，且要求它是唯一的启用管理员");
        realAdminId = realAdmin.getId();
        assertEquals(1, countEnabledAdmins(), "前置条件：除 admin 外不应有其他启用管理员");

        tempAId = createAdminUser(TEMP_PREFIX + "a_");
        tempBId = createAdminUser(TEMP_PREFIX + "b_");

        // 临时把真实 admin 移出启用集合，让并发场景恰好只有两个启用管理员
        userService.lambdaUpdate()
                .eq(User::getId, realAdminId)
                .set(User::getRole, (byte) 1)
                .update();
        assertEquals(2, countEnabledAdmins(), "场景前置：必须恰好两个启用管理员参与互相降级");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Result<String>> demoteB = pool.submit(() -> {
                ready.countDown();
                go.await();
                return asAdmin(tempAId, () -> adminUserController.updateRole(tempBId, roleRequest((byte) 1)));
            });
            Future<Result<String>> demoteA = pool.submit(() -> {
                ready.countDown();
                go.await();
                return asAdmin(tempBId, () -> adminUserController.updateRole(tempAId, roleRequest((byte) 1)));
            });

            assertTrue(ready.await(10, TimeUnit.SECONDS), "两个并发请求都应就绪");
            go.countDown();

            Result<String> result1 = demoteB.get(30, TimeUnit.SECONDS);
            Result<String> result2 = demoteA.get(30, TimeUnit.SECONDS);

            long successCount = List.of(result1, result2).stream()
                    .filter(r -> r.getCode() != null && r.getCode() == 200)
                    .count();
            assertEquals(1, successCount, "行锁串行化后应恰好一个降级成功，另一个被拦截，实际 r1="
                    + result1 + ", r2=" + result2);
            Result<String> rejected = List.of(result1, result2).stream()
                    .filter(r -> r.getCode() != null && r.getCode() == 500)
                    .findFirst()
                    .orElseThrow();
            assertEquals("系统必须保留至少一个管理员", rejected.getMessage());
            assertEquals(1, countEnabledAdmins(), "两个管理员互相降级后必须仍保留一个管理员");
            assertEquals(1, countEnabledAdminsAmong(tempAId, tempBId), "剩余管理员应是两个临时管理员之一");
        } finally {
            pool.shutdownNow();
        }
    }

    private long countEnabledAdmins() {
        return userService.lambdaQuery()
                .eq(User::getRole, (byte) 3)
                .eq(User::getIsBanned, (byte) 0)
                .count();
    }

    private long countEnabledAdminsAmong(Long... ids) {
        return userService.lambdaQuery()
                .eq(User::getRole, (byte) 3)
                .eq(User::getIsBanned, (byte) 0)
                .in(User::getId, List.of(ids))
                .count();
    }

    private Long createAdminUser(String prefix) {
        User user = new User();
        String username = prefix + System.nanoTime();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("Codex@123456"));
        user.setNickname(username);
        user.setRole((byte) 3);
        user.setIsBanned((byte) 0);
        userService.save(user);
        return user.getId();
    }

    private static AdminUserController.RoleRequest roleRequest(byte role) {
        AdminUserController.RoleRequest request = new AdminUserController.RoleRequest();
        request.setRole(role);
        return request;
    }

    private static <T> T asAdmin(Long userId, Callable<T> action) throws Exception {
        User actor = new User();
        actor.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        actor, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
