package com.musealgo.oj.analytics.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量生成周报请求。
 */
@Data
public class BatchReportRequest {

    @NotNull(message = "班级 ID 不能为空")
    private Long classId;

    @NotEmpty(message = "学生 ID 列表不能为空")
    @Size(max = 50, message = "单次最多生成 50 份报告")
    private List<@NotNull Long> studentIds;
}
