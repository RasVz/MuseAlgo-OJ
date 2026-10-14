package top.everblue.oj.judge.service;

import com.baomidou.mybatisplus.extension.service.IService;
import top.everblue.oj.judge.entity.UserCodeDraft;

/**
 * 代码草稿服务接口。
 * <p>
 * 契约只有「按三元组取」与「按三元组存」两条，但两条都以 (userId, problemId, language) 作为唯一键，
 * 这个约束<strong>必须由服务层保证</strong>：直接暴露 {@code IService} 的 save/update 会让调用方绕过唯一性，
 * 写出重复行，之后 {@code getDraft} 到底返回哪一条就成了不确定行为。
 * </p>
 * <p>
 * 保存语义是 upsert，天然适配前端防抖触发的重复保存；查询未命中返回 {@code null} 而不是空对象，
 * 调用方无需判空即可判断「是新题」还是「有草稿」。
 * </p>
 */
public interface UserCodeDraftService extends IService<UserCodeDraft> {

    // 获取草稿
    UserCodeDraft getDraft(Long userId, Long problemId, String language);

    // 保存或更新草稿
    boolean saveOrUpdateDraft(Long userId, Long problemId, String language, String code);
}