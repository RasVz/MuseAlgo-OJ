package top.everblue.oj.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识文章与练习题的多对多关联；题解不使用此表。 */
@Data
@TableName("post_problem_relation")
public class PostProblemRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private Long problemId;
    private LocalDateTime createTime;
}
