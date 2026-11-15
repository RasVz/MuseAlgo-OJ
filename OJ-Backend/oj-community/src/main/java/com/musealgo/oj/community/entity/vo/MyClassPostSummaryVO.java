package com.musealgo.oj.community.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的内容 → 班级内容」一级列表：我发过动态的班级及数量。
 */
@Data
public class MyClassPostSummaryVO {
    private Long classId;
    private String className;
    private Long postCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastPostTime;
}
