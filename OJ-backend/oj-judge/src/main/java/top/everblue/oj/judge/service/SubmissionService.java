package top.everblue.oj.judge.service;

import com.baomidou.mybatisplus.spring.service.IService;
import top.everblue.oj.judge.entity.Submission;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评测记录表 服务类
 */
public interface SubmissionService extends IService<Submission> {
    Submission submitCode(Submission submission);

    List<Map<String, Object>> getUserDailySubmissionStats(Long userId, LocalDateTime startTime, LocalDateTime endTime);

}
