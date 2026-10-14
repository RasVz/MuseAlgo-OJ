package top.everblue.oj.judge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.judge.entity.UserCodeDraft;
import top.everblue.oj.judge.mapper.UserCodeDraftMapper;
import top.everblue.oj.judge.service.UserCodeDraftService;

/**
 * 代码草稿服务实现：先查后写的 upsert。
 * <p>
 * <strong>这里是「先查再写」而不是数据库 ON DUPLICATE，存在一个已知窗口</strong>：
 * 同一用户同一题在极短时间内的并发保存（多标签页同时打开）会同时查到「不存在」，
 * 各自 insert 一条，撞上唯一键后其中一方抛重复键异常。
 * 前端已用防抖把并发概率压得很低，且草稿丢失的代价极低（退回模板代码），
 * 因此没有换成 ON DUPLICATE——那需要在 XML 里维护一份与实体重复的 SQL。
 * 如果将来草稿改成真正的用户资产（比如作业代码），这里必须换成 upsert。
 * </p>
 * <p>
 * 更新走 {@code updateById}（主键定位）而不是按三元组 update：草稿主键存在，
 * 按主键更新最省且不会误伤其他行。
 * </p>
 */
@Service
public class UserCodeDraftServiceImpl extends ServiceImpl<UserCodeDraftMapper, UserCodeDraft> implements UserCodeDraftService {

    @Override
    public UserCodeDraft getDraft(Long userId, Long problemId, String language) {
        // 根据 用户ID、题目ID、语言 查询唯一的一条草稿
        return this.getOne(new LambdaQueryWrapper<UserCodeDraft>()
                .eq(UserCodeDraft::getUserId, userId)
                .eq(UserCodeDraft::getProblemId, problemId)
                .eq(UserCodeDraft::getLanguage, language));
    }

    @Override
    public boolean saveOrUpdateDraft(Long userId, Long problemId, String language, String code) {
        // 先查询是否已经有草稿了
        UserCodeDraft existDraft = getDraft(userId, problemId, language);

        if (existDraft == null) {
            // 没有草稿，新增一条
            UserCodeDraft newDraft = new UserCodeDraft();
            newDraft.setUserId(userId);
            newDraft.setProblemId(problemId);
            newDraft.setLanguage(language);
            newDraft.setCode(code);
            return this.save(newDraft);
        } else {
            // 已有草稿，更新代码内容
            existDraft.setCode(code);
            return this.updateById(existDraft);
        }
    }
}