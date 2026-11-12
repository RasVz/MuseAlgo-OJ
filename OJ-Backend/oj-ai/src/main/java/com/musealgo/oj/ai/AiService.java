package com.musealgo.oj.ai;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.musealgo.oj.ai.gateway.AiGateway;
import com.musealgo.oj.ai.gateway.PromptRegistry;
import com.musealgo.oj.ai.dto.AiAskOutcome;
import com.musealgo.oj.ai.guard.AiContestGuard;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.api.ai.AiAssistant;
import com.musealgo.oj.ai.dto.AiStreamResult;

import java.util.function.Consumer;

/**
 * AI 助教编排层：负责拼装对话、读写历史、注入检索资料、决定降级策略。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>模型传输 + 供应商切换/缓存 → {@link AiGateway}</li>
 *   <li>提示词模板（版本化）→ {@link PromptRegistry}</li>
 *   <li>会话历史 → {@link AiChatSessionService}</li>
 *   <li>检索增强（RAG）→ {@link RagContextService}</li>
 *   <li>本类只做"拼装 + 编排"</li>
 * </ul>
 */
@Slf4j
@Service
public class AiService implements AiAssistant {

    private static final String API_FAIL_FALLBACK = "糟糕，教练的大脑暂时短路了，请稍后再试一下吧！";

    private final AiGateway aiGateway;
    private final AiChatSessionService sessionService;
    private final RagContextService ragContextService;
    private final AiQuotaService quotaService;
    private final AiContestGuard contestGuard;
    private final TeacherWorkbenchService teacherWorkbenchService;
    private final ReviewApplicationService reviewApplicationService;
    private final com.musealgo.oj.ai.tutor.TutorMessageBuilder messages;
    private final com.musealgo.oj.ai.diagnostics.TutorDiagnosisService diagnosis;
    private final com.musealgo.oj.ai.usage.AiUsageRecorder usage;

    public AiService(AiGateway aiGateway, AiChatSessionService sessionService,
                     RagContextService ragContextService, AiQuotaService quotaService,
                     AiContestGuard contestGuard, TeacherWorkbenchService teacherWorkbenchService,
                     ReviewApplicationService reviewApplicationService,
                     com.musealgo.oj.ai.tutor.TutorMessageBuilder messages) {
        this(aiGateway, sessionService, ragContextService, quotaService, contestGuard, teacherWorkbenchService, reviewApplicationService, messages, null, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AiService(AiGateway aiGateway, AiChatSessionService sessionService,
                     RagContextService ragContextService, AiQuotaService quotaService,
                     AiContestGuard contestGuard, TeacherWorkbenchService teacherWorkbenchService,
                     ReviewApplicationService reviewApplicationService, com.musealgo.oj.ai.tutor.TutorMessageBuilder messages,
                     com.musealgo.oj.ai.diagnostics.TutorDiagnosisService diagnosis, com.musealgo.oj.ai.usage.AiUsageRecorder usage) {
        this.diagnosis = diagnosis; this.usage=usage;
        this.aiGateway = aiGateway; this.sessionService = sessionService;
        this.ragContextService = ragContextService; this.quotaService = quotaService;
        this.contestGuard = contestGuard; this.teacherWorkbenchService = teacherWorkbenchService;
        this.reviewApplicationService = reviewApplicationService; this.messages = messages;
    }

    /**
     * 教师侧 AI 工具（A4）：命题草稿 / 测试数据 / 学情解读。
     * 非流式，失败返回降级文案；不写对话历史。
     * <p>
     * 契约实现：{@link AiAssistant#chatWithTool}。
     */
    @Override
    public String chatWithTool(String toolKey, String userPrompt) {
        long start=System.currentTimeMillis();
        var result=teacherWorkbenchService.teacherChatDetailed(toolKey, userPrompt);
        recordIndirect(com.musealgo.oj.common.utils.SecurityUtils.getUserId(),"TOOL",result,userPrompt,start);
        return result.success()?result.content():API_FAIL_FALLBACK;
    }

    /**
     * 命题工作台（B）：结构化 JSON 生成（题面 / std / 测试数据 / IP 改名抽取）。
     * 使用 jsonMode 输出，失败返回 null，由调用方决定降级文案；不写对话历史。
     * <p>
     * 契约实现：{@link AiAssistant#chatJsonWithTool}。
     */
    @Override
    public String chatJsonWithTool(String toolKey, String userPrompt) {
        long start=System.currentTimeMillis();
        var result=teacherWorkbenchService.teacherWorkbenchJsonDetailed(toolKey, userPrompt);
        recordIndirect(com.musealgo.oj.common.utils.SecurityUtils.getUserId(),"TOOL_JSON",result,userPrompt,start);
        return result.success()?result.content():null;
    }

    /** 从模型输出中稳健提取 JSON 对象：容忍 ```json 围栏与前后说明文字；失败返回 null。 */
    public static JSONObject extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.contains("```")) {
            t = t.replaceAll("```[a-zA-Z0-9]*", "").replace("```", "").trim();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(t.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(systemPrompt));
        messages.add(userMessage(userPrompt));
        long start=System.currentTimeMillis();
        var result=aiGateway.completeDetailed(messages,false);
        recordIndirect(com.musealgo.oj.common.utils.SecurityUtils.getUserId(),"INDIRECT",result,messages.toString(),start);
        return result.success()?result.content():API_FAIL_FALLBACK;
    }

    @Override
    public String chatJson(String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(systemPrompt));
        messages.add(userMessage(userPrompt));
        // 失败返回 null，由调用方自行处理；不返回降级文案，避免把失败伪装成 JSON 内容
        long start=System.currentTimeMillis();
        var result=aiGateway.completeDetailed(messages,true);
        recordIndirect(com.musealgo.oj.common.utils.SecurityUtils.getUserId(),"INDIRECT_JSON",result,messages.toString(),start);
        return result.success()?result.content():null;
    }

    @Override
    public String chatJsonForUser(Long user, String systemPrompt, String userPrompt) {
        JSONArray messages = new JSONArray();
        messages.add(systemMessage(systemPrompt));
        messages.add(userMessage(userPrompt));
        // 后台任务没有请求上下文，显式用调用方传入的用户记录用量
        long start = System.currentTimeMillis();
        var result = aiGateway.completeDetailed(messages, true);
        recordIndirect(user, "INDIRECT_JSON", result, messages.toString(), start);
        return result.success() ? result.content() : null;
    }

    @Override public String chatForUser(Long user, String systemPrompt, String userPrompt) {
        JSONArray request=new JSONArray();request.add(systemMessage(systemPrompt));request.add(userMessage(userPrompt));
        long start=System.currentTimeMillis();var result=aiGateway.completeDetailed(request,false);
        recordIndirect(user,"ADVICE",result,request.toString(),start);
        return result.success()?result.content():API_FAIL_FALLBACK;
    }
    private void recordIndirect(Long user,String scene,com.musealgo.oj.ai.dto.AiCompletionResult result,String prompt,long start) {
        if(usage==null||result==null)return;
        boolean estimated=!result.cached()&&result.usage()==null;
        int input=result.cached()?0:result.usage()!=null?result.usage().promptTokens():result.success()?estimateTokens(prompt):0;
        int output=result.cached()?0:result.usage()!=null?result.usage().completionTokens():result.success()?estimateTokens(result.content()):0;
        try { usage.record(user,null,scene,input,output,estimated,System.currentTimeMillis()-start,result.success()?AiUsage.STATUS_SUCCESS:AiUsage.STATUS_FAIL,
                result.cached()?"缓存命中，未调用供应商":estimated?"未获供应商用量，按可得内容估算（工具模板可能未计入）":result.errorType(),result.source()); }
        catch(RuntimeException failure){log.warn("间接 AI 用量记录不可用：{}",failure.getClass().getSimpleName());}
    }

    static final String CONTEST_BLOCKED_NOTICE = "比赛场景不提供 AI 教学帮助，请到普通练习中使用。";
    static final String QUOTA_EXCEEDED_NOTICE = "今日 AI 使用额度已用完，额度将于次日重置。";

    public AiAskOutcome streamChatGuarded(Long userId, com.musealgo.oj.ai.dto.AiAskRequest request,
            com.musealgo.oj.api.problem.ProblemTutoringQuery.Context problem, boolean privileged,
            Consumer<String> onChunk) {
        if (contestGuard.shouldBlock(request.getContestId()))
            return AiAskOutcome.blocked(AiUsage.STATUS_CONTEST_BLOCKED, CONTEST_BLOCKED_NOTICE);
        if (!quotaService.tryAcquire(userId, privileged))
            return AiAskOutcome.blocked(AiUsage.STATUS_QUOTA_REJECTED, QUOTA_EXCEEDED_NOTICE);
        try (var lease = sessionService.lock(userId, request.getProblemId())) {
            JSONArray conversation = messages.build("tutor-open", problem, request.getStudentCode(), request.getLanguage(),
                    request.getStudentQuestion(), request.getHelpMode(), diagnosis == null ? ragContextService.buildReference(request.getProblemId()) : teachingReference(request.getProblemId()),
                    sessionService.loadHistory(userId, request.getProblemId()), diagnosis == null ? null : diagnosis.assessment(userId, request));
            String prompt = com.musealgo.oj.ai.tutor.TutorMessageBuilder.flatten(conversation);
            AiStreamResult result = diagnosis == null ? aiGateway.stream(conversation, onChunk) : diagnosis.stream(userId, request, conversation, onChunk);
            AiAskOutcome outcome = outcome(result, prompt);
            if (AiUsage.STATUS_SUCCESS.equals(outcome.status()))
                sessionService.appendTurn(userId, request.getProblemId(), request.getStudentQuestion(), result.content());
            return outcome.withProblemId(request.getProblemId());
        }
    }

    private String teachingReference(Long problem) {
        String reference=diagnosis.reference(problem);
        try { String supplement=ragContextService.buildSupplement(problem); return supplement==null ? reference : reference+"\n【补充材料，可能不完整】\n"+supplement.substring(0,Math.min(2000,supplement.length())); }
        catch(RuntimeException failure){ log.warn("教学补充材料暂不可读：{}",failure.getClass().getSimpleName()); return reference; }
    }

    static AiAskOutcome outcome(AiStreamResult result, String prompt) {
        if (result == null) return AiAskOutcome.fail(prompt);
        return new AiAskOutcome(result.success() ? AiUsage.STATUS_SUCCESS
                : "CANCELLED".equals(result.errorType()) ? AiUsage.STATUS_CANCELLED : AiUsage.STATUS_FAIL,
                result.content(), result.usage(), prompt, result.source(), result.errorType(), null, result.requestSent(), result.usageEstimated());
    }

    public ReviewApplicationService.PreparedReview prepareReview(Long userId, com.musealgo.oj.ai.dto.AiReviewRequest request) {
        return reviewApplicationService.prepare(userId, request);
    }
    public AiAskOutcome reviewSubmissionStream(Long userId, com.musealgo.oj.ai.dto.AiReviewRequest request,
            ReviewApplicationService.PreparedReview review, boolean privileged, Consumer<String> onChunk) {
        return reviewApplicationService.stream(userId, request, review, privileged, onChunk);
    }

    /** 字符数 → token 粗估：中英混合语料约 2 字符/token（偏保守，宁多勿少） */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }

    private static JSONObject systemMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "system");
        msg.set("content", content);
        return msg;
    }

    private static JSONObject userMessage(String content) {
        JSONObject msg = new JSONObject();
        msg.set("role", "user");
        msg.set("content", content);
        return msg;
    }
}
