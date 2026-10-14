package top.everblue.oj.problem.service;

import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.problem.entity.ProblemCase;
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
}
