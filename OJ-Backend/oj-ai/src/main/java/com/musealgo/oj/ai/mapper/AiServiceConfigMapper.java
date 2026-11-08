package com.musealgo.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.musealgo.oj.ai.entity.AiServiceConfig;

import java.util.List;

/** AI 服务配置仓储；所有需并发保护的方法都必须在事务内调用。 */
@Mapper
public interface AiServiceConfigMapper extends BaseMapper<AiServiceConfig> {

    @Select("SELECT * FROM ai_service_config WHERE id = #{id} AND is_deleted = 0")
    AiServiceConfig selectActiveById(@Param("id") Long id);

    @Select("SELECT * FROM ai_service_config WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    AiServiceConfig selectActiveByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM ai_service_config WHERE is_deleted = 0 ORDER BY id ASC")
    List<AiServiceConfig> selectAllActive();
}
