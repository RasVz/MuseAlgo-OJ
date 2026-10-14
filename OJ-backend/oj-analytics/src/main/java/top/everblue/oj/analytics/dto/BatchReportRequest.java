package top.everblue.oj.analytics.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
    private List<Long> studentIds;
}
