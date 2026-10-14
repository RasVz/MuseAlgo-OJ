package top.everblue.oj.judge.infrastructure;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.submission.HomeworkSubmissionPageRequest;
import top.everblue.oj.api.submission.HomeworkSubmissionQuery;
import top.everblue.oj.api.submission.HomeworkSubmissionSummary;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.judge.mapper.SubmissionMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 作业提交情况查询适配器：供 oj-teaching 展示「某次作业每道题谁提交了、结果如何」。
 * <p>
 * 跨越模块边界（teaching 要读 submission 的数据），因此按项目约定走 oj-api 的接口契约：
 * oj-api 定义 {@link HomeworkSubmissionQuery} 与 DTO，实现放在数据所属的 oj-judge，
 * oj-teaching 只依赖接口。<strong>不要让 oj-teaching 直接注入 SubmissionMapper</strong>，
 * 那会让 oj-teaching 反向依赖 oj-judge 的内部结构，拆表（submission / submission_code / submission_result）
 * 时就会连带波及教学模块。
 * </p>
 * <p>
 * 底层走 {@code selectHomeworkSubmissionPage} 的多表 JOIN，一次查出学生、题目与提交信息；
 * 拆成多次查询再拼接在这个场景下没有收益——批量列表本来就要全量返回。
 * </p>
 * <p>
 * 转换层的 {@code longValue} 等方法必须做<strong>类型容错</strong>而不是直接强转：
 * JOIN 返回的 Map 没有编译期类型，MySQL 驱动在不同列类型下给出的可能是
 * {@code Long} / {@code BigInteger} / {@code BigDecimal}，直接 {@code (Long) row.get(...)}
 * 会在某些环境下抛 ClassCastException，且只在特定数据量下才复现。
 * </p>
 */
@Service
public class HomeworkSubmissionQueryAdapter implements HomeworkSubmissionQuery {

    private final SubmissionMapper submissionMapper;

    public HomeworkSubmissionQueryAdapter(SubmissionMapper submissionMapper) {
        this.submissionMapper = submissionMapper;
    }

    @Override
    public PageResult<HomeworkSubmissionSummary> findPage(HomeworkSubmissionPageRequest request) {
        IPage<Map<String, Object>> page = submissionMapper.selectHomeworkSubmissionPage(
                new Page<>(request.page(), request.size()), request.classId(), request.homeworkId(), request.studentId(),
                request.problemId(), request.startTime(), request.endTime());
        List<HomeworkSubmissionSummary> records = page.getRecords().stream()
                .map(this::toSummary)
                .toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    private HomeworkSubmissionSummary toSummary(Map<String, Object> row) {
        return new HomeworkSubmissionSummary(
                longValue(row.get("id")), longValue(row.get("studentId")), stringValue(row.get("studentName")),
                longValue(row.get("problemId")), stringValue(row.get("problemTitle")), stringValue(row.get("language")),
                byteValue(row.get("status")), stringValue(row.get("score")), intValue(row.get("runTime")),
                intValue(row.get("runMemory")), stringValue(row.get("errorMessage")), localDateTimeValue(row.get("createTime")));
    }

    private Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private Integer intValue(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private Byte byteValue(Object value) { return value instanceof Number number ? number.byteValue() : null; }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private LocalDateTime localDateTimeValue(Object value) { return value instanceof LocalDateTime dateTime ? dateTime : null; }
}
