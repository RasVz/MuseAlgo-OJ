package top.everblue.oj.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 系统配置 KV（DB 覆盖 yml 默认值）。 */
@Getter
@Setter
@TableName("system_config")
public class SystemConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "config_key", type = IdType.INPUT)
    private String configKey;

    private String configValue;

    private LocalDateTime updatedAt;
}
