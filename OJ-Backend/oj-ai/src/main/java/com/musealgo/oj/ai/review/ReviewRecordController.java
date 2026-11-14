package com.musealgo.oj.ai.review;

import com.musealgo.oj.common.annotation.RateLimit;
import com.musealgo.oj.common.api.Result;
import com.musealgo.oj.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/reviews")
@RequiredArgsConstructor
@Validated
public class ReviewRecordController {
    private final ReviewRecordService records;
    @PostMapping
    @RateLimit(key="aiTeaching",action="AI 复盘",time=60,count=12)
    public Result<ReviewView> create(@Valid @RequestBody ReviewCreateRequest request) {
        return Result.success(records.create(SecurityUtils.getUserId(),SecurityUtils.isTeacherOrAdmin(),request));
    }
    @GetMapping
    public Result<ReviewRecordService.RecordPage> list(@RequestParam(defaultValue="1") int currentPage,
            @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) @Positive Long submissionId,
            @RequestParam(required=false) @Positive Long problemId, @RequestParam(required=false) @Size(max=128) String keyword,
            @RequestParam(required=false) String status) {
        return Result.success(records.list(SecurityUtils.getUserId(),currentPage,pageSize,submissionId,problemId,keyword,status));
    }
    @GetMapping("/{id}")
    public Result<java.util.Map<String,Object>> detail(@PathVariable @Positive Long id) {
        return Result.success(records.detail(SecurityUtils.getUserId(),id));
    }
    @GetMapping("/{id}/status")
    public Result<ReviewView> status(@PathVariable @Positive Long id) {
        return Result.success(records.status(SecurityUtils.getUserId(),id));
    }
    @PostMapping("/{id}/cancel")
    public Result<ReviewView> cancel(@PathVariable @Positive Long id) {
        return Result.success(records.cancel(SecurityUtils.getUserId(),id));
    }
}
