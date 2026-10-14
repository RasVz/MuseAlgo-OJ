package top.everblue.oj.api.submission;

import java.time.LocalDateTime;

/** 提交列表安全只读视图 — 绝不携带源码与诊断信息，供跨模块消费。 */
public class SubmissionSummary {
    private Long id;
    private Long problemId;
    private String problemTitle;
    private Long userId;
    private String username;
    private String userAvatar;
    private Byte status;
    private String judgePhase;
    private String language;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProblemId() { return problemId; }
    public void setProblemId(Long problemId) { this.problemId = problemId; }
    public String getProblemTitle() { return problemTitle; }
    public void setProblemTitle(String problemTitle) { this.problemTitle = problemTitle; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserAvatar() { return userAvatar; }
    public void setUserAvatar(String userAvatar) { this.userAvatar = userAvatar; }
    public Byte getStatus() { return status; }
    public void setStatus(Byte status) { this.status = status; }
    public String getJudgePhase() { return judgePhase; }
    public void setJudgePhase(String judgePhase) { this.judgePhase = judgePhase; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
