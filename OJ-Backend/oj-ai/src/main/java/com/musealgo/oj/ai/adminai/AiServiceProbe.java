package com.musealgo.oj.ai.adminai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.musealgo.oj.ai.AiService;
import com.musealgo.oj.ai.dto.AiCompletionResult;
import com.musealgo.oj.ai.dto.AiStreamResult;
import com.musealgo.oj.ai.gateway.ChatCompletionsClient;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定短探针：只验证协议兼容性，不发送学生代码或聊天历史。
 * 普通文本与流式文本必测；JSON 模式启用时追加协议级 JSON 检查。
 */
@Component
@RequiredArgsConstructor
public class AiServiceProbe {

    private final ChatCompletionsClient chatClient;

    public AiServiceAdminDtos.TestReport probe(AiServiceSnapshot snapshot, int configVersion) {
        return probe(snapshot, configVersion, chatClient);
    }

    public AiServiceAdminDtos.TestReport probe(AiServiceSnapshot snapshot, int configVersion,
                                               ChatCompletionsClient client) {
        long started = System.currentTimeMillis();
        LocalDateTime testedAt = LocalDateTime.now();
        List<AiServiceAdminDtos.TestCheck> checks = new ArrayList<>();

        long textStart = System.currentTimeMillis();
        AiCompletionResult text = client.complete(snapshot, messages(false), false);
        checks.add(new AiServiceAdminDtos.TestCheck(
                "TEXT", text.success(), false, System.currentTimeMillis() - textStart,
                text.errorType(), text.success() ? "普通文本调用通过" : text.errorMessage()));

        long streamStart = System.currentTimeMillis();
        AiStreamResult stream = client.stream(snapshot, messages(false), ignored -> {
        });
        checks.add(new AiServiceAdminDtos.TestCheck(
                "STREAM", stream.success() && stream.emitted(), false, System.currentTimeMillis() - streamStart,
                stream.errorType(),
                stream.success() && stream.emitted() ? "流式文本调用通过" : stream.errorMessage()));

        if (snapshot.jsonMode()) {
            long jsonStart = System.currentTimeMillis();
            AiCompletionResult json = client.complete(snapshot, messages(true), true);
            JSONObject parsed = json.success() ? AiService.extractJsonObject(json.content()) : null;
            boolean jsonSuccess = parsed != null && !parsed.isEmpty();
            checks.add(new AiServiceAdminDtos.TestCheck(
                    "JSON", jsonSuccess, false, System.currentTimeMillis() - jsonStart,
                    jsonSuccess ? null : json.errorType() == null ? "INVALID_RESPONSE" : json.errorType(),
                    jsonSuccess ? "JSON 输出通过" : json.errorMessage() == null
                            ? "上游未返回有效 JSON" : json.errorMessage()));
        } else {
            checks.add(new AiServiceAdminDtos.TestCheck(
                    "JSON", false, true, 0, "SKIPPED", "协议级 JSON 已关闭，未执行"));
        }

        boolean passed = checks.stream().filter(check -> !check.skipped()).allMatch(AiServiceAdminDtos.TestCheck::success);
        return new AiServiceAdminDtos.TestReport(snapshot.serviceId(), configVersion, passed, testedAt,
                System.currentTimeMillis() - started, List.copyOf(checks));
    }

    public AiServiceAdminDtos.TestReport cryptoUnavailable(Long serviceId, int configVersion, String message) {
        return new AiServiceAdminDtos.TestReport(serviceId, configVersion, false, LocalDateTime.now(), 0,
                List.of(new AiServiceAdminDtos.TestCheck(
                        "CRYPTO", false, false, 0, "CRYPTO_UNAVAILABLE",
                        message == null ? "AI 服务密钥不可用" : message)));
    }

    private static JSONArray messages(boolean json) {
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.set("role", "system");
        system.set("content", json
                ? "Return one JSON object only, without markdown. Required field: ok."
                : "You are a connectivity probe. Follow the user instruction exactly.");
        messages.add(system);
        JSONObject user = new JSONObject();
        user.set("role", "user");
        user.set("content", json ? "Return {\"ok\":true}." : "Reply with the single word OK.");
        messages.add(user);
        return messages;
    }
}
