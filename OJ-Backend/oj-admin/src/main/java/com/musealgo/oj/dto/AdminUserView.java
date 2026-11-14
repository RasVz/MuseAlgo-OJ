package com.musealgo.oj.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 管理端用户列表/详情的只读视图。
 * <p>列表只填充常用字段，详情额外填充资料与统计数据；不包含密码等敏感字段。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminUserView {

    private Long id;
    private String username;
    private String nickname;
    private String realName;
    private String email;
    private Byte role;
    private String school;
    private String major;
    private String avatar;
    private String introduction;

    @JsonProperty("isBanned")
    private Boolean isBanned;
    private Boolean banActive;
    private String banReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime banExpireTime;

    private Integer submissionCount;
    private Integer acceptedCount;
    private Integer rating;
    private Integer level;
    private Integer experience;
    private Integer followerCount;
    private Integer followingCount;
    private Integer likeCount;
    private Long aiTokens;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastLoginTime;
}
