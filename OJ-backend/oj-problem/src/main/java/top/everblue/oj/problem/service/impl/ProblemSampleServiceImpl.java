package top.everblue.oj.problem.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.problem.entity.ProblemSample;
import top.everblue.oj.problem.mapper.ProblemSampleMapper;
import top.everblue.oj.problem.service.ProblemSampleService;

/**
 * 题目样例服务实现。
 * <p>
 * 全部能力来自 {@link ServiceImpl}，因此本类<b>故意留空</b>。
 * 样例的"整组替换"语义在应用层（样例相关应用服务）用事务包住，
 * 而不是在这里加一个 updateAll 之类的方法——放在应用层才能和题面字段同事务落库。
 * </p>
 */
@Service
public class ProblemSampleServiceImpl extends ServiceImpl<ProblemSampleMapper, ProblemSample>
        implements ProblemSampleService {
}
