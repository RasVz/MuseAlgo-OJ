package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.judge.entity.SubmissionCode;

/**
 * 提交源码 Mapper（submission_code，V50 拆表）。
 */
@Mapper
public interface SubmissionCodeMapper extends BaseMapper<SubmissionCode> {
}
