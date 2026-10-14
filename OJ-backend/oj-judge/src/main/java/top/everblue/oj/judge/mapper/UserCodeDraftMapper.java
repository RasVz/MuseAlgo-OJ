package top.everblue.oj.judge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import top.everblue.oj.judge.entity.UserCodeDraft;

/**
 * 代码草稿 Mapper，唯一性由数据库的 (user_id, problem_id, language) 唯一键兜底。
 * <p>
 * 刻意不定义任何自定义 SQL：草稿的读写都只是等值条件，{@link com.baomidou.mybatisplus.core.mapper.BaseMapper}
 * 的通用方法已足够。写 SQL 只会多一份需要与实体保持同步的定义。
 * </p>
 * <p>
 * 注意本接口没有 {@code @Mapper} 注解：它由启动类的 {@code @MapperScan} 统一注册
 * （见 {@code top.everblue.oj.OjApplication}）。<strong>新增 mapper 时也不要单独加 {@code @Mapper}</strong>——
 * 两种注册方式混用虽然能工作，但会让「哪些 mapper 已被扫描」变得难以核对。
 * </p>
 */
public interface UserCodeDraftMapper extends BaseMapper<UserCodeDraft> {
}