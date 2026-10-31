package com.musealgo.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import com.musealgo.oj.ai.entity.AiRuntimeConfig;

/** 单行运行时配置；锁定读取必须在事务内。 */
@Mapper
public interface AiRuntimeConfigMapper extends BaseMapper<AiRuntimeConfig> {

    @Select("SELECT * FROM ai_runtime_config WHERE id = 1")
    AiRuntimeConfig selectSingleton();

    @Select("SELECT * FROM ai_runtime_config WHERE id = 1 FOR UPDATE")
    AiRuntimeConfig lockSingleton();
}
