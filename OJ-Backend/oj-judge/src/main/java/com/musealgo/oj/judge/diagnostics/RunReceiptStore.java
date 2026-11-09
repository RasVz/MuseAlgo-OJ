package com.musealgo.oj.judge.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.judge.CodeExecutionGateway.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunReceiptStore {
    public record Stored(Long problemId, String fingerprint, Observation observation) { }
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public void save(Long user, Long problem, String code, String language, Observation observation) {
        try { redis.opsForValue().set(key(user, observation.runId()),
                json.writeValueAsString(new Stored(problem, fingerprint(code, language, observation.input()), observation)), Duration.ofMinutes(30)); }
        catch (Exception failure) { log.warn("运行回执未保存：{}", failure.getClass().getSimpleName()); }
    }
    public Observation read(Long user, Long problem, String code, String language, String id) {
        if (id == null || !id.matches("[a-fA-F0-9-]{36}")) return null;
        try {
            String raw = redis.opsForValue().get(key(user, id));
            if (raw == null) return null;
            var stored = json.readValue(raw, Stored.class);
            if (!java.util.Objects.equals(problem, stored.problemId()) || !fingerprint(code, language, stored.observation().input()).equals(stored.fingerprint())) return null;
            return stored.observation();
        } catch (Exception failure) { log.warn("运行回执不可用：{}", failure.getClass().getSimpleName()); return null; }
    }
    private static String key(Long user, String id) { return "ai:run-receipt:" + user + ":" + id; }
    static String fingerprint(String code, String language, String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{code, language, input}) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
