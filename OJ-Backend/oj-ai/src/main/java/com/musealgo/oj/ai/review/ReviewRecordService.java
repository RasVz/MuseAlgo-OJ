package com.musealgo.oj.ai.review;

import com.musealgo.oj.ai.mapper.ReviewMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.ai.ReviewApplicationService;
import com.musealgo.oj.ai.ReviewApplicationService.PreparedReview;
import com.musealgo.oj.ai.dto.*;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.api.problem.ProblemTutoringQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery;
import com.musealgo.oj.api.submission.SubmissionReviewQuery.SubmissionReviewData;
import com.musealgo.oj.common.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReviewRecordService {
    static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "CANCELLING");
    static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED");
    private final ReviewMapper mapper;
    private final ReviewApplicationService reviews;
    private final SubmissionReviewQuery submissions;
    private final AiQuotaService quota;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public record Created(Long id) { }
    public record StopRequested(Long id) { }
    /** 请求线程读取的授权材料快照；源码只引用原提交，不重复存储。 */
    public record Input(SubmissionReviewData data, ProblemTutoringQuery.Context problem, String systemPrompt, String promptHash) { }
    public record RecordPage(List<ReviewView> items, long total, long currentPage, long pageSize) { }

    public ReviewView create(Long user, boolean privileged, ReviewCreateRequest request) {
        requireUser(user);
        String fingerprint = hash(write(Arrays.asList(request.getSubmissionId(), request.getHelpMode(),
                request.getStudentQuestion(), request.getParentReviewId(), request.getRelationType())));
        SubmissionReview replay = byRequest(user, request.getRequestId());
        if (replay != null) return replay(replay, fingerprint);
        validateRelation(user, request);
        AiReviewRequest teaching = teachingRequest(user, request);
        PreparedReview prepared = reviews.prepare(user, teaching);
        var conversation = reviews.conversation(teaching, prepared); // 输入预算和比赛拦截在落库前完成
        if (!quota.tryAcquire(user, privileged)) throw new BusinessException(AiErrorCode.AI_QUOTA_EXCEEDED);
        var data = prepared.data();
        SubmissionReview record = new SubmissionReview();
        record.setUserId(user); record.setSubmissionId(data.id()); record.setProblemId(data.problemId());
        record.setRequestId(request.getRequestId()); record.setRequestHash(fingerprint);
        record.setParentReviewId(request.getParentReviewId()); record.setRelationType(request.getRelationType().name());
        record.setStatus("QUEUED"); record.setProblemTitle(prepared.problem().title());
        record.setLanguage(data.language()); record.setSubmissionStatus(data.status()); record.setScore(data.score());
        record.setRunTime(data.runTime()); record.setRunMemory(data.runMemory());
        record.setHelpMode(request.getHelpMode().name()); record.setStudentQuestion(request.getStudentQuestion());
        record.setCodeHash(hash(data.code())); record.setPrivileged(privileged);
        record.setInputJson(write(new Input(withCode(data, null), prepared.problem(), conversation.getJSONObject(0).getStr("content"),
                hash(com.musealgo.oj.ai.tutor.TutorMessageBuilder.flatten(conversation)))));
        record.setLatencyMs(0L); record.setPromptTokens(0); record.setCompletionTokens(0);
        record.setTokensEstimated(false); record.setCreateTime(LocalDateTime.now()); record.setUpdateTime(record.getCreateTime());
        try {
            // 单语句自动提交：唯一(user,request_id)和唯一活动提交同时保护跨实例并发。
            mapper.insert(record);
        } catch (DuplicateKeyException collision) {
            replay = byRequest(user, request.getRequestId());
            if (replay != null) return replay(replay, fingerprint);
            // 另一个请求键不能冒充同一次成功受理，否则其响应丢失后再重发无法幂等。
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "该提交已有复盘正在生成，请从复盘记录查看");
        }
        events.publishEvent(new Created(record.getId()));
        return ReviewView.of(record);
    }

    private SubmissionReview byRequest(Long user, String requestId) {
        return mapper.selectOne(new LambdaQueryWrapper<SubmissionReview>()
                .eq(SubmissionReview::getUserId, user).eq(SubmissionReview::getRequestId, requestId));
    }
    private ReviewView replay(SubmissionReview record, String fingerprint) {
        if (!fingerprint.equals(record.getRequestHash()))
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "请求编号已被其他复盘内容使用");
        return ReviewView.of(record);
    }
    private void validateRelation(Long user, ReviewCreateRequest request) {
        boolean child = request.getRelationType() == ReviewCreateRequest.Relation.FOLLOW_UP
                || request.getRelationType() == ReviewCreateRequest.Relation.RETRY;
        if (child != (request.getParentReviewId() != null))
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "追问和重试需要对应的原复盘记录");
        if (!child) return;
        SubmissionReview parent = owned(user, request.getParentReviewId(), false);
        if (!parent.getSubmissionId().equals(request.getSubmissionId()))
            throw new BusinessException(AiErrorCode.AI_INPUT_INVALID, "原复盘与当前提交不一致");
        if (request.getRelationType() == ReviewCreateRequest.Relation.FOLLOW_UP) {
            if (!"SUCCEEDED".equals(parent.getStatus()) || request.getStudentQuestion() == null || request.getStudentQuestion().isBlank())
                throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "请对已完成的复盘输入追问");
        } else if (!Set.of("FAILED", "CANCELLED").contains(parent.getStatus())) {
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT, "只有失败或停止的复盘可以重试");
        }
    }
    AiReviewRequest teachingRequest(Long user, ReviewCreateRequest request) {
        AiReviewRequest teaching = new AiReviewRequest();
        teaching.setSubmissionId(request.getSubmissionId()); teaching.setHelpMode(request.getHelpMode());
        teaching.setStudentQuestion(request.getStudentQuestion());
        if (request.getRelationType() == ReviewCreateRequest.Relation.FOLLOW_UP) {
            SubmissionReview parent = owned(user, request.getParentReviewId(), true);
            if (!"SUCCEEDED".equals(parent.getStatus())) throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT);
            teaching.setPreviousAnswer(clip(parent.getAnswer(), 11000));
        }
        return teaching;
    }
    AiReviewRequest teachingRequest(SubmissionReview record) {
        ReviewCreateRequest request = new ReviewCreateRequest();
        request.setSubmissionId(record.getSubmissionId()); request.setHelpMode(AiTeachingOptions.HelpMode.valueOf(record.getHelpMode()));
        request.setStudentQuestion(record.getStudentQuestion()); request.setParentReviewId(record.getParentReviewId());
        request.setRelationType(ReviewCreateRequest.Relation.valueOf(record.getRelationType()));
        return teachingRequest(record.getUserId(), request);
    }

    public RecordPage list(Long user, int currentPage, int pageSize, Long submissionId, Long problemId, String keyword, String state) {
        requireUser(user);
        if (state != null && !state.isBlank() && !STATES.contains(state)) throw new BusinessException(AiErrorCode.AI_INPUT_INVALID);
        var filter = new LambdaQueryWrapper<SubmissionReview>().eq(SubmissionReview::getUserId, user)
                .eq(submissionId != null, SubmissionReview::getSubmissionId, submissionId)
                .eq(problemId != null, SubmissionReview::getProblemId, problemId)
                .eq(state != null && !state.isBlank(), SubmissionReview::getStatus, state);
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            Long parsedId = null;
            String numeric = term.replaceFirst("(?i)^P", "");
            if (numeric.matches("[1-9][0-9]{0,18}")) {
                try { parsedId = Long.valueOf(numeric); } catch (NumberFormatException ignored) { /* 按题名搜索超范围数字 */ }
            }
            final Long titleOrId = parsedId;
            filter.and(q -> {
                q.like(SubmissionReview::getProblemTitle, term);
                if (titleOrId != null) q.or().eq(SubmissionReview::getProblemId, titleOrId);
            });
        }
        filter.orderByDesc(SubmissionReview::getId);
        var page = mapper.selectPage(new Page<SubmissionReview>(Math.max(1, currentPage), Math.max(1, Math.min(100, pageSize))), filter);
        return new RecordPage(page.getRecords().stream().map(ReviewView::of).toList(), page.getTotal(), page.getCurrent(), page.getSize());
    }
    public ReviewView status(Long user, Long id) { return ReviewView.of(owned(user, id, false)); }
    public Map<String,Object> detail(Long user, Long id) {
        SubmissionReview record = owned(user, id, true);
        Map<String,Object> view = json.convertValue(ReviewView.of(record), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() { });
        view.put("answer", record.getAnswer()); view.put("codeHash", record.getCodeHash());
        var source = submissions.findById(record.getSubmissionId());
        String code = source.filter(s -> user.equals(s.userId()) && Objects.equals(record.getCodeHash(), hash(s.code())))
                .map(SubmissionReviewData::code).orElse(null);
        view.put("code", code);
        return view;
    }
    SubmissionReview owned(Long user, Long id, boolean full) {
        requireUser(user);
        SubmissionReview record = full ? mapper.full(id) : mapper.selectById(id);
        if (record == null || !user.equals(record.getUserId())) throw new BusinessException(AiErrorCode.AI_REVIEW_NOT_FOUND);
        return record;
    }
    public ReviewView cancel(Long user, Long id) {
        SubmissionReview record = owned(user, id, false);
        if (ACTIVE.contains(record.getStatus())) {
            // 排队尚未执行可立即终止；运行中先占住活动槽，等 worker 关闭上游后再释放。
            mapper.update(null, new LambdaUpdateWrapper<SubmissionReview>().eq(SubmissionReview::getId,id)
                    .eq(SubmissionReview::getUserId,user).eq(SubmissionReview::getStatus,"QUEUED")
                    .set(SubmissionReview::getStatus,"CANCELLED").set(SubmissionReview::getErrorCode,"AI_REVIEW_CANCELLED")
                    .set(SubmissionReview::getMessage,"已停止生成")
                    .set(SubmissionReview::getFinishTime,LocalDateTime.now()).set(SubmissionReview::getUpdateTime,LocalDateTime.now()));
            mapper.update(null, new LambdaUpdateWrapper<SubmissionReview>().eq(SubmissionReview::getId,id)
                    .eq(SubmissionReview::getUserId,user).eq(SubmissionReview::getStatus,"RUNNING")
                    .set(SubmissionReview::getStatus,"CANCELLING").set(SubmissionReview::getMessage,"正在停止生成")
                    .set(SubmissionReview::getUpdateTime,LocalDateTime.now()));
            events.publishEvent(new StopRequested(id));
        }
        return status(user,id);
    }
    public boolean claim(Long id, String execution) {
        return mapper.update(null,new LambdaUpdateWrapper<SubmissionReview>().eq(SubmissionReview::getId,id)
                .eq(SubmissionReview::getStatus,"QUEUED").set(SubmissionReview::getStatus,"RUNNING")
                .set(SubmissionReview::getExecutionId,execution).set(SubmissionReview::getStartTime,LocalDateTime.now())
                .set(SubmissionReview::getUpdateTime,LocalDateTime.now())) == 1;
    }
    Input input(SubmissionReview record) { return read(record.getInputJson(), Input.class); }
    PreparedReview material(SubmissionReview record) {
        Input input = input(record);
        var source = submissions.findById(record.getSubmissionId()).orElseThrow(() -> new BusinessException(AiErrorCode.AI_REVIEW_SUBMISSION_NOT_FOUND));
        if (!record.getUserId().equals(source.userId()) || source.examAttemptId() != null || source.contestId() != null
                || !record.getCodeHash().equals(hash(source.code())))
            throw new BusinessException(AiErrorCode.AI_STATE_CONFLICT,"原提交已变化，无法继续复盘");
        return new PreparedReview(withCode(input.data(), source.code()), input.problem());
    }
    static SubmissionReviewData withCode(SubmissionReviewData d, String code) {
        return new SubmissionReviewData(d.id(), d.userId(), d.problemId(), d.status(), d.language(), code,
                d.score(), d.runTime(), d.runMemory(), d.errorMessage(), d.contestId(), d.caseStats(), d.examAttemptId());
    }
    static void requireUser(Long user) { if (user == null) throw new BusinessException(CommonErrorCode.AUTH_REQUIRED); }
    static String clip(String value, int length) { return value == null ? null : value.substring(0,Math.min(value.length(),length)); }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("复盘输入序列化失败", failure); }
    }
    <T> T read(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch(JsonProcessingException failure) { throw new IllegalStateException("复盘输入读取失败", failure); }
    }
}
