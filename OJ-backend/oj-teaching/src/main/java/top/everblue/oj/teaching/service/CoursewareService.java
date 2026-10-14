package top.everblue.oj.teaching.service;

import top.everblue.oj.teaching.entity.Courseware;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 课件资源表 服务类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface CoursewareService extends IService<Courseware> {

    /**
     * 批量统计课件被班级使用数量（coursewareId → cnt）。
     */
    Map<Long, Integer> countClassUses(Collection<Long> coursewareIds);
}
