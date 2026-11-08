package com.musealgo.oj.problem.service;

import org.springframework.web.multipart.MultipartFile;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.baomidou.mybatisplus.spring.service.IService;
import java.util.List;

/**
 * <p>
 * 判题用例表 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemCaseService extends IService<ProblemCase> {
    int importCasesFromZip(MultipartFile file, Long problemId);

    List<ProblemCase> getProblemCases(Long problemId);

    /**
     * 读取用例集合的「当前已提交」版本，供标准答案保存前的最后一致性校验使用。
     * <p>
     * 与 {@link #getProblemCases} 的区别是这里用 {@code FOR SHARE} 做锁定读：
     * 普通 SELECT 在 REPEATABLE READ 下返回事务第一次读时的快照，快照可能早于
     * 本次加锁时刻，于是「拿到题目的写锁之后再去比对指纹」仍可能比到旧数据。
     * 锁定读永远返回最新已提交版本，才能保证比对结果与写入同一时刻。
     * </p>
     * <p>必须在事务中调用；无事务时加锁读等价于普通读（锁立即释放），不能用于一致性判断。</p>
     */
    List<ProblemCase> getProblemCasesForShare(Long problemId);
}
