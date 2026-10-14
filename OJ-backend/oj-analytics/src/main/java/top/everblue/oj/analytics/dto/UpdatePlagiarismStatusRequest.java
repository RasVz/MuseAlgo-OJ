package top.everblue.oj.analytics.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新查重报告处置状态请求（PENDING / REVIEWED / IGNORED）。
 */
@Data
public class UpdatePlagiarismStatusRequest {

    @NotBlank(message = "状态不能为空")
    private String status;
}
