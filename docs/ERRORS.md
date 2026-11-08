# 错误响应约定

更新：2026-11-07。

普通业务接口继续返回 `{code,message,data}`，只有 `code=200` 表示成功。失败响应新增可选 `errorCode`、`traceId`、`details`，成功数据结构保持原样。

```json
{
  "code": 409,
  "errorCode": "EXAM_ATTEMPT_EXPIRED",
  "message": "作答时间已结束，试卷已自动交卷",
  "data": null,
  "traceId": "request-trace-id",
  "details": { "autoSubmitted": true }
}
```

- `code` 表示粗分类，`errorCode` 表示稳定业务原因。客户端依据原因码分支，文案仅用于显示。
- 普通业务失败沿用 HTTP 200；Security 拒绝保留真实 HTTP 401/403，认证依赖故障为 HTTP 503，报告 HTML 预览保留真实错误状态。客户端必须同时支持 body 错误和 HTTP 错误。
- 不能生成任何可接受表示时返回 HTTP 406 空响应，避免错误响应再次触发内容协商异常。代理先行拦截的错误可能不是应用 JSON。
- 未知 Java 异常（包括未明确分类的 IllegalArgumentException）是内部故障，返回固定安全提示和 `INTERNAL_ERROR`。输入错误须在请求边界显式表达，不能用 Java 异常类型猜测业务原因。
- 参数校验的 `details.fieldErrors` 最多 20 项，仅包含有界字段名、校验码和提示，不包含被拒值。限流使用 `details.retryAfterSeconds`，不解析中文消息中的秒数。
- `traceId` 复用 `X-Trace-Id` 响应头和日志上下文，客户端可由响应头回退读取。不能把 SQL、路径、Token、密码、原始供应商响应或堆栈塞进 message/details。

## 错误定义与使用

共享传输和认证原因在 `oj-common/.../exception/CommonErrorCode.java`。业务原因由所属模块的 `error/*ErrorCode.java` 定义。例如：

| 原因码 | body code | 场景 |
| --- | --- | --- |
| REQUEST_BODY_INVALID | 400 | JSON 不能解析或缺少必要请求体 |
| REQUIRED_PARAMETER_MISSING / PARAMETER_TYPE_MISMATCH / VALIDATION_FAILED | 400 | 缺参数、类型不匹配、校验失败 |
| AUTH_REQUIRED / AUTH_TOKEN_EXPIRED / AUTH_TOKEN_INVALID / AUTH_TOKEN_REVOKED | 401 | 缺少登录、过期、无效、已撤销的凭据 |
| AUTH_INVALID_CREDENTIALS / AUTH_REFRESH_INVALID | 401 | 登录凭据错误、refresh 无效 |
| ACCESS_DENIED | 403 | 当前主体无权访问 |
| RESOURCE_NOT_FOUND / ROUTE_NOT_FOUND | 404 | 路径或资源不存在 |
| METHOD_NOT_ALLOWED / MEDIA_TYPE_UNSUPPORTED | 405 / 415 | 请求方法或内容类型不支持 |
| UPLOAD_TOO_LARGE | 413 | 应用上传大小限制 |
| AUTH_USERNAME_TAKEN / AUTH_EMAIL_TAKEN | 409 | 注册信息冲突 |
| SUBMISSION_NOT_FOUND / PROBLEM_NOT_FOUND | 404 | 有权调用者查询的提交或题目不存在 |
| CONTEST_NOT_ENDED / CONTEST_JUDGING_PENDING | 409 | 比赛结算条件未满足 |
| CONTEST_PROBLEM_ALREADY_ADDED | 409 | 比赛已添加该题 |
| CONTEST_NOT_FOUND | 404 | 比赛不存在或已删除，包括成绩 CSV 导出 |
| CLASS_MEMBER_NOT_FOUND | 404 | 管理员或所属教师移除的班级成员不存在 |
| PROBLEM_LIST_ITEM_NOT_FOUND | 404 | 题单移除或排序引用了不属于该题单的题目 |
| EXAM_ATTEMPT_EXPIRED | 409 | 超时已自动交卷；保留交卷事务更新 |
| RATE_LIMITED / AUTH_LOGIN_COOLDOWN | 429 | 请求限流、账号冷却 |
| DEPENDENCY_UNAVAILABLE / AUTH_DEPENDENCY_UNAVAILABLE | 503 | 已确认的依赖不可用，仍拒绝授权 |
| AI_RESPONSE_INVALID / AI_UPSTREAM_FAILED | 502 / 503 | 教师工作台返回无效内容或上游不可用 |
| UPSTREAM_TIMEOUT | 504 | 已识别的上游超时 |
| INTERNAL_ERROR | 500 | 未预期内部故障 |

业务层使用 `BusinessException(ErrorCode, safeMessage)` 或其默认文案构造器；控制器直接拒绝使用 `ErrorResponses.failure(...)`。必须保留特定异常的事务语义，例如 `AttemptExpiredException` 的 `noRollbackFor`。真实未知数据库故障不能转成普通 409，只有已知的业务约束冲突才能这样处理。

MyBatis 包装的已知连接/查询超时原因仍归依赖不可用；包装的 SQL 用法或约束错误保留 `INTERNAL_ERROR`。真实存在且无成绩的比赛仍可导出只有表头的 CSV。教师工作台只对成功调用但 JSON 损坏做格式修复重试，连接失败或超时不进入格式修复。

必要写入必须检查实际结果，失败不能继续返回“保存成功”。题单成员变更与计数更新在同一事务中完成；批量排序任一项失败会回滚先前修改。公告、题单、角色/禁用及班级课件写入失败保留实际失败语义。偏好保存和已登录排名查询的数据库异常交给统一处理，匿名首页的未上榜标记仍为 `-1`。

审计与AI用量日期筛选支持原有ISO日期/时间格式；非法值返回 `PARAMETER_TYPE_MISMATCH`，`details.field` 标明 `start` 或 `end`，不回显传入值。日期精度和原有筛选上下界语义保持不变。

旧的 `Result.error(String)`、`fail(String)` 保留默认 500 以兼容既有调用者；新业务代码使用上述明确的错误定义。报告等旧协议的已知原因码通过 `ErrorDefinition` 适配，不接收任意供应商错误码。

前端 `request.js` 将普通 JSON、真实 HTTP 错误和 JSON Blob 错误归一化为 `ApiError`，保留 `error.response` 及原因、详情和追踪信息。`silent` 可抑制本次全局提示；同一失败只提示一次。只有明确 access 过期的请求进入单飞刷新，旧后端无原因码的真实 401 保留兼容；重放最多一次。依赖故障不应清空会话或持续刷新。

较晚返回的过期请求可复用本模块刚完成的同一会话凭据轮换，避免重复刷新。切换账号、退出或匿名请求发出后才登录时，不以新身份重放旧操作；旧刷新结果不能覆盖新的登录会话。无法关联到当前会话的旧请求返回客户端 `AUTH_SESSION_CHANGED`，由调用者重新发起操作。

## 文件、批量结果与流式响应

下载成功仍是文件，报告预览成功仍是 HTML。失败是 JSON 时按内容类型恢复错误协议，不能强行包装成功内容。

批量导入、格式化和报告的逐项失败提供可选 `errorCode`，保留原有结果字段。批量报告先校验共享日期范围；只把可逐项处理的业务拒绝放进列表，依赖和未知内部异常继续传播，不伪装成批量成功。

AI 的流式握手可能返回普通 JSON 错误，即使 HTTP 为 200 也需要检查 Content-Type。推流后使用：

```text
event: app-error
data: {"code":503,"errorCode":"AI_UPSTREAM_FAILED","message":"AI 服务暂时不可用，请稍后重试"}
```

成功结束才发送 `event: done`。`app-error`、网络读取失败和无 done 的提前 EOF 都是未完成，不能作为成功回答或据此解锁下一级提示。前端保留已有部分文本并标记未完成；断连仅清理资源，不向已关闭的流补写普通 JSON。排行榜 SSE 沿用已有连接和权限协议。


## 后台处理与派生状态

普通提交必须同时保存提交记录、源码和判题任务；任一必要写入失败会回滚账号提交计数。重复报名、并发收藏和举报结案通过数据库锁或条件更新保持状态与计数一致；举报只允许从 `PENDING` 结案，重复处置返回 `STATE_CONFLICT`。结案后可再次举报，历史记录保留。

关闭 RabbitMQ 时，持久 outbox 仍处理本地标签 Rating 投影，并归档已发布事件。相同用户的投影串行重建，重复消息不重复累计 Rating。开启 RabbitMQ 后，标签处理失败最多尝试三次，再保留原事件至持久队列 `oj.rating.failed`，附安全原因 `TAG_RATING_PROCESSING_FAILED`；失败消息不附带原始数据库异常。outbox 自身失败按既有有界重试进入可查询失败状态。帖子收藏的缓存副作用与数据库主事实分开处理，只在事务提交后更新，缓存故障不把已完成的数据库写入误报为未保存。

AI 复盘的不存在提交、权限拒绝和无可复盘源码分别使用资源缺失、权限拒绝、状态冲突原因；实际上游失败继续使用上游错误原因。流式错误仍使用上述 `app-error`，不会把拒绝文案当作正常回答。
