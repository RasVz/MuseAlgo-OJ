# 管理中心 AI 服务

管理中心 →「AI 与运维」→「AI 服务」（`/admin/ai-services`）允许管理员维护多条 Chat Completions 兼容配置，并选择其中一条供全站使用。首版一个服务对应一个地址、一个模型和一把密钥；同一地址使用不同模型时添加多条记录。

## 一、运行模式

| 模式 | 请求来源 | 失败行为 |
|---|---|---|
| `LEGACY` | 环境变量 `DEEPSEEK_API_URL` / `DEEPSEEK_API_KEY` 与旧动态模型配置 | 保留原有「未配置 Key 则降级」行为 |
| `MANAGED` | 管理页选中的服务记录，URL、模型、密钥来自同一配置版本 | 不自动退回环境变量或其他服务 |
| `OFF` | 无 | 不发送模型请求，业务返回既有降级文案 |

部署升级后默认仍是 `LEGACY`。管理员在服务页明确点击「设为当前」才进入 `MANAGED`；「停用 AI」进入 `OFF`。`OFF` 即使旧环境 Key 仍在也不会外呼。

每次请求在开始时读取一次运行时和完整配置快照；切换、编辑、停用只影响之后的新请求。已输出内容的流式请求不会跨服务重试。

## 二、启用步骤

### 开发环境一条命令初始化

在仓库根目录执行：

```bash
python3 scripts/ai-services-init.py --start
```

命令会优先复用当前后端已挂载的主密钥；首次生成前检查开发数据库没有服务密文，并以 600 权限原子创建文件，然后应用只读挂载、启动并等待后端就绪。不会覆盖已有文件。若数据库已有密文但主密钥丢失，命令会停止，需恢复原文件。主密钥须独立安全备份；生产环境继续采用下面的运维挂载流程。

页面会显示主密钥是否可读且格式有效，并在失败时区分部署初始化与表单错误。文件就绪不代表存量服务密文一定可解密。

### 1. 生成独立主密钥

服务商 Key 需要可解密后放进出站请求头，因此使用 AES-256-GCM 认证加密保存，不能像登录密码那样只存哈希。

```bash
mkdir -p deploy/secrets
openssl rand -base64 32 > deploy/secrets/oj_ai_master_key
chmod 600 deploy/secrets/oj_ai_master_key
```

主密钥必须独立备份，不能复用 `JWT_SECRET`，不能写进源码、数据库或迁移。仅有数据库备份无法解密服务商 Key。

### 2. 只读挂载

开发：

```bash
OJ_AI_MASTER_KEY_FILE_HOST="$PWD/deploy/secrets/oj_ai_master_key" \
docker compose -f compose.dev.yml -f deploy/compose.dev.ai-service-secret.yml up -d backend
```

生产：

```bash
# Linux 发行镜像以 UID/GID 10001 运行，文件属主必须与容器内读取者匹配。
sudo chown 10001:10001 deploy/secrets/oj_ai_master_key
sudo chmod 400 deploy/secrets/oj_ai_master_key
OJ_AI_MASTER_KEY_FILE_HOST=/secure/path/oj_ai_master_key \
docker compose -f docker-compose.prod.yml -f deploy/compose.ai-service-secret.yml up -d
```

上例的 `/secure/path/oj_ai_master_key` 应替换为已设置属主和权限的同一个文件的绝对路径。只读挂载不会自动修正属主；Docker Desktop 与用户命名空间环境需以容器内的实际权限为准。[Docker bind mount 说明](https://docs.docker.com/engine/storage/bind-mounts/)。

启动前可用发行镜像做不输出文件内容的读取检查（需要已填写生产环境变量）：

```bash
OJ_AI_MASTER_KEY_FILE_HOST=/secure/path/oj_ai_master_key \
docker compose -f docker-compose.prod.yml -f deploy/compose.ai-service-secret.yml \
  run --rm --no-deps --entrypoint sh backend \
  -c 'test -r /run/secrets/oj_ai_master_key && test ! -w /run/secrets/oj_ai_master_key'
```

生产叠加文件同时挂载到 `backend` 与 `report-worker`，使 Web 与 worker 读取同一主密钥。运行模式保存在数据库：首次升级默认为 `LEGACY`；已切到 `MANAGED` 的实例不会因为取消挂载自动恢复旧配置。此时缺少主密钥只使受影响的 AI 调用失败，其他 OJ 功能继续运行。

### 3. 添加、测试、切换

1. 点「添加服务」，填写官网 Base URL（例如 `https://api.deepseek.com`、`https://api.example.com/v1`）或完整请求地址、精确模型 ID 和 API Key。默认自动识别根地址与 `/v1` 等版本前缀并补齐 `/chat/completions`，完整路径不重复拼接；自定义基础前缀可选择“基础地址”，需要原样使用路径时选择“完整请求地址”。页面展示保存后使用的实际请求地址。
2. 后端对最终完整 URL 执行原有安全检查；只保存完整请求地址。已有旧版 Base URL 记录需编辑保存一次，地址方式选“自动识别”、API Key 留空保留；保存后版本增加，原测试结果过期，需重新测试。不会静默改写记录或绕过当前服务的“测试并保存应用”。
3. 保存后是「未测试」的备选，不会被自动使用。
4. 点「测试连接」。测试发送固定短探针，分别验证普通文本、流式文本，启用 JSON 模式时再验证 JSON；不会发送学生代码或聊天历史。
5. 测试通过后点「设为当前」，切换立即对之后的新请求生效，无需重启后端。
6. 编辑当前服务时使用「测试并保存应用」：候选配置先在事务外测试，全部通过且版本未变化才原子保存；失败或并发修改时原配置继续使用。

普通连接测试和候选测试共用每个应用进程的 4 个并发槽，同一服务不能被不同管理员重复测试。每项探针的连接 / 读取 / 整体调用上限为 5 / 20 / 30 秒，最多顺序执行三项；每次调用前地址检查最多 8 秒，这两个页面请求使用独立的 130 秒超时，其余管理请求保持原有上限。

## 三、密钥与地址安全

- 管理接口只返回「已配置 / 未配置」，不返回明文或密文，也不提供复制。
- 新增必须提供 Key；编辑留空表示保留，显式空值或纯星号占位串会被拒绝。
- 主密钥缺失、密文损坏或认证失败时，受影响服务安全失败；不会回退明文或另一把密钥。
- 公网地址只允许 HTTPS 443，禁止用户信息、query、fragment 和重定向。
- 保存、测试、正式调用共用 SSRF 校验；新连接只能采用校验后的解析地址，保留原域名的 Host、SNI 与证书校验。客户端明确直连、禁用 HTTP/2 跨主机连接合并；精确私网例外使用独立且不保留空闲连接的连接池。
- 系统 DNS 全部返回 `198.18.0.0/15` 时，后端自动通过内置加密 DNS 恢复真实公网解析，无需修改系统 DNS 或启动代理。`198.18/15` 不因此成为公网地址；字面 IP、其他非公网或混合结果仍被拒绝。原有精确 URL 私网例外保留其含义。
- 解析恢复只用于疑似 fake-ip，不对系统解析失败、企业私网回答或安全拒绝自动换解析结果。DoH 同时查询 A/AAAA，两项都正常完成（允许某项没有记录）后才返回；任一查询异常则失败。DoH 全部地址仍须通过公网检查。
- 默认解析服务为 `https://dns.alidns.com/dns-query`，bootstrap 为该解析基础设施官方入口 `223.5.5.5,223.6.6.6`，不固定 AI 供应商 IP。仅将目标域名发给解析服务，不发送 API Key、路径或业务内容。
- 运维可通过 `OJ_AI_FAKE_IP_RECOVERY=false` 回退为仅系统解析；`OJ_AI_DOH_URL` 和 `OJ_AI_DOH_BOOTSTRAP` 可配置受信任的 HTTPS 443 解析服务及公网 IP，管理页不能修改。解析服务配置应纳入部署变更审计。
- 地址解析总预算 8 秒，采用 4 个专用线程和 16 项排队上限，无新增应用 DNS 缓存。原生系统 DNS 调用不一定能被中断；若全部解析线程被阻塞，后续请求会有界失败，等待系统解析恢复。该状态不占用无限线程。
- 断网、解析服务不可达或目标出口不通时给出环境错误，不标记测试通过、不切换当前配置。恢复解析不承诺突破网络出口限制。
- 需要连接本机/内网测试服务时，只能由运维在 `OJ_AI_PRIVATE_TARGETS` 配置**精确完整 URL**，多个用逗号分隔；管理页没有跳过开关。
- 连接阶段的私网例外也只对该完整 URL 生效，不扩大到同主机的其他路径和端口。
- 上游错误只映射为受控类型，不把供应商原始正文写入浏览器或日志。
- 流式单行在等待换行时即受大小限制；没有完成标记就结束的部分回答按中断处理，不计为完整成功，也不跨服务重试。

## 四、版本与并发

- 每次编辑备选服务都会增加配置版本，旧测试结果标记「已过期」。
- 「设为当前」要求同版本测试通过；测试结果绑定版本，过期结果不能作为新配置的通过证明。
- 当前服务正在编辑时，事务内同时核对服务版本、运行时版本和当前服务 ID。
- 当前服务不能直接停用或删除；必须先切换，或明确停用全站 AI。
- 启用备选不会自动设为当前，也不替代连接测试。
- 删除是软删除；历史 `ai_usage` 保留，旧流水的来源字段保持 `NULL`，不回填伪造。

## 五、缓存与用量

- 缓存键包含运行模式、服务 ID、配置版本、模型、生成参数和完整消息；A 切到 B 后相同消息不会命中 A 的回答。
- JSON 模式不缓存。
- 用量记录实际服务 ID、服务名称、模型、运行模式和配置版本；正在运行的请求在切换后仍按开始时的服务记账。
- 连接测试不写 `ai_usage`，不占学生每日配额。
- 多服务不套用统一单价；页面保留 Token 统计并提示费用以服务商账单为准。

## 六、主密钥轮换与恢复

首版不保存主密钥 ID，也不支持双密钥并行解密。轮换需要维护窗口：

1. 切到 `LEGACY` 或 `OFF`，确认没有 `MANAGED` 请求。
2. 备份数据库与旧主密钥文件，两者都保留。
3. 生成新主密钥，替换只读挂载文件并重启 `backend` / `report-worker`。
4. 管理员逐条编辑服务，重新输入服务商 Key（新主密钥下无法解密旧密文），重新测试。
5. 全量确认后删除旧主密钥；若失败，恢复旧密钥文件与切回原模式。

回滚：代码回退不需要删除 V67 表；切回 `LEGACY` 可恢复旧环境配置。V67 只新增表和可空列，旧版本代码可忽略。

## 七、首版边界

- 只支持 Chat Completions 兼容协议；不自动发现模型、不轮询多 Key、不自动故障转移、不按场景分配模型。
- 不建设价格目录。
- 真实供应商兼容性必须在提供真实配置后单独联调；模拟上游通过不等于所有服务商都可用。
- 加密保护数据库独立泄露场景，不等同于抵御应用主机完全失陷。

## 八、管理员接口

全部要求 `ADMIN`，浏览器经 `/api` 代理：

| 方法与路径 | 用途 |
|---|---|
| `GET /admin/ai/services` | 服务列表（无密钥） |
| `POST /admin/ai/services` | 新增 |
| `POST /admin/ai/services/diagnose` | 检查目标解析和安全策略，不调用模型；每分钟 10 次 |
| `PUT /admin/ai/services/{id}` | 编辑；当前服务测试后应用 |
| `POST /admin/ai/services/{id}/test` | 测试已保存版本 |
| `POST /admin/ai/services/{id}/activate` | 原子设为当前 |
| `POST /admin/ai/services/{id}/enable` / `disable` | 启用 / 停用备选 |
| `DELETE /admin/ai/services/{id}` | 软删除 |
| `GET /admin/ai/runtime` | 当前模式与安全摘要 |
| `PUT /admin/ai/runtime` | 停用或恢复旧环境，需当前运行时版本 |

常见业务码：`400` 参数/URL/密钥格式，`403` 非管理员，`409` 版本或状态冲突，`429` 测试限频/并发已满，`503` 主密钥不可用。


细分错误：`AI_MASTER_KEY_NOT_READY` 表示主密钥需要初始化或恢复，`AI_SERVICE_KEY_UNAVAILABLE` 表示服务密文不可用，`AI_DNS_UNAVAILABLE` 表示解析环境失败，`AI_ENDPOINT_FORBIDDEN` 表示地址违反安全策略。探针里的 `AI_TARGET_UNREACHABLE` 表示后端无法建立目标连接，凭证、超时与协议失败仍有独立类型。管理页“检查地址”只验证解析和地址规则，不能代替保存后的连接测试。


HTTP 404 的探针类型为 `UPSTREAM_NOT_FOUND`，提示检查完整请求路径与模型名称。它与模型不可用、认证失败分开，不回显供应商原始错误正文。填写方式 `addressType` 为可选输入字段（`AUTO` / `BASE_URL` / `FULL_URL`），不在数据库新增配置列；诊断响应的 `apiUrl` 是服务器已校验的完整地址，不表示凭证或模型测试通过。
