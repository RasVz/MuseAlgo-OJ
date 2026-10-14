package top.everblue.oj.problem.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 作答会话 VO（含剩余秒数）。 */
@Data
public class ExamAttemptVO {

    private Long id;
    private Long paperId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime submitTime;
    private Integer objectiveScore;
    private Integer codingScore;
    private Integer totalScore;
    private Long remainingSeconds;
}
