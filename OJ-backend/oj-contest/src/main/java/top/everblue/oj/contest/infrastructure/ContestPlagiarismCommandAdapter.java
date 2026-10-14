package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.contest.ContestPlagiarismCommand;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.mapper.ContestMapper;

/**
 * 比赛查重状态写契约实现（oj-analytics 赛后自动查重调度器消费）。
 */
@Service
@RequiredArgsConstructor
public class ContestPlagiarismCommandAdapter implements ContestPlagiarismCommand {

    private final ContestMapper contestMapper;

    @Override
    public void markContestChecked(Long contestId) {
        contestMapper.update(null, new LambdaUpdateWrapper<Contest>()
                .eq(Contest::getId, contestId)
                .set(Contest::getPlagiarismChecked, 1));
    }
}
