package top.everblue.oj.teaching.entity.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课件列表展示对象。
 * <p>
 * 存在理由：列表页需要额外展示教师昵称与头像，这两项在 courseware 表里并不存在。
 * 与其让前端按 teacher_id 再发一次请求（或在 Mapper 里做一次 JOIN 然后每个列表接口各写一遍组装逻辑），
 * 不如在服务层一次组装好。<strong>凡是只服务于列表展示的字段都应放这里</strong>，
 * 不要污染 courseware 实体——那是持久化模型，加了不该有的字段会让它名不副实。
 * </p>
 * <p>
 * 两个需要注意的字段：
 * </p>
 * <ul>
 *   <li>{@code url} 是 Slidev 课件的访问地址，其中<strong>uuid 目录名不可改动</strong>——
 *       它与磁盘目录一一对应且已入库，改名会让所有历史链接立即失效。</li>
 *   <li>{@code content} 是 Markdown 源码，会让列表响应体明显变大。若列表页并不展示源码
 *       （多数场景不需要），应在服务层查列表时<strong>不 select 这一列</strong>，
 *       而不是等前端抱怨加载慢再优化。</li>
 * </ul>
 * <p>
 * {@code privacy} 为 0 时课件仅对绑定班级可见，列表查询必须带上这个条件，
 * 否则会把未发布的课件混进公开列表。
 * </p>
 */
@Data
@ApiModel(value = "CoursewareListVO对象", description = "课件列表展示对象（含教师信息）")
public class CoursewareListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("课件ID")
    private Long id;

    @ApiModelProperty("创建老师ID")
    private Long teacherId;

    @ApiModelProperty("创建老师用户名/昵称")
    private String teacherName;

    @ApiModelProperty("创建老师头像")
    private String teacherAvatar;

    @ApiModelProperty("课件标题")
    private String title;

    @ApiModelProperty("Slidev访问地址")
    private String url;

    @ApiModelProperty("课件类型: 0-Slidev网页, 1-腾讯文档嵌入")
    private Integer type;

    @ApiModelProperty("关联题单ID")
    private Long problemListId;

    @ApiModelProperty("Slidev Markdown源码")
    private String content;

    @ApiModelProperty("简介")
    private String description;

    @ApiModelProperty("0-私有, 1-公开")
    private Integer privacy;

    @ApiModelProperty("被几个班级使用")
    private Integer classUseCount;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;

    @ApiModelProperty("更新时间")
    private LocalDateTime updateTime;
}
