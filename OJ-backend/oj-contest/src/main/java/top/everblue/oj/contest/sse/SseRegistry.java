package top.everblue.oj.contest.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接注册表。
 * <p>
 * 按比赛维度维护「当前正在看直播的观众连接」，提供注册、定向广播与自动清理三件事。
 * 注册表只保存连接句柄，<b>不持有任何线程</b>：广播动作由调用方（消息消费者）在其自身线程里同步执行，
 * 因此不存在后台线程、也没有轮询循环——没有数据时连接就静默挂着，不产生任何流量。
 * </p>
 *
 * <h3>为什么用 SseEmitter 而不是 WebSocket</h3>
 * <p>
 * 榜单是<b>单向</b>推送（服务端 → 浏览器），浏览器不需要通过同一条链路回传任何东西。
 * SSE 基于普通 HTTP，天然穿透反向代理与企业网关的 WebSocket Upgrade 拦截，
 * 断线后浏览器内置的 EventSource 会自动重连并带上 {@code Last-Event-ID}，
 * 而 WebSocket 需要自己实现心跳与重连协议。这里换取的能力不值得为单向推送引入双向通道的复杂度。
 * </p>
 *
 * <h3>连接生命周期</h3>
 * <ol>
 *   <li><b>建立</b>：Controller 创建 {@code new SseEmitter(0L)}（0 表示不设服务端超时）并注册；
 *       Spring 把该请求线程交还给容器，响应转为「长轮询直到写数据或连接断开」。</li>
 *   <li><b>存活</b>：连接只存在于本 Map 中，是否继续占用资源取决于对端 TCP 状态。
 *       本模块<b>不下发服务端心跳帧</b>，因此部分反向代理（Nginx 的 {@code proxy_read_timeout} 默认 60s）
 *       可能在无数据时切断空闲连接——这是「比赛长时间无人提交」时的已知现象，
 *       依赖浏览器侧 EventSource 自动重连兜底，不是漏写代码。</li>
 *   <li><b>清理</b>：三条回调任一触发即摘除该连接（正常结束 / 超时 / IO 异常），
 *       列表空后连同比赛维度的 Map 条目一并移除，避免空 Map 无限堆积。</li>
 * </ol>
 *
 * <h3>线程模型与数据结构选择</h3>
 * <p>
 * 写入（注册/清理）来自 Tomcat 请求线程，广播来自 RabbitMQ 消费线程，两侧并发访问同一个 List。
 * 用 {@link CopyOnWriteArrayList}：读的频率（每次提交都全量广播）远高于写（仅连接建立/断开），
 * 写时复制让广播可以无锁遍历，代价是断开时一次 O(n) 数组拷贝，在几十~几百连接量级可忽略。
 * 外层 {@link ConcurrentHashMap} 保证「按比赛隔离」这一维度本身线程安全。
 * </p>
 */
@Slf4j
@Component
public class SseRegistry {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public java.util.Set<Long> activeContestIds() { return java.util.Set.copyOf(emitters.keySet()); }

    /**
     * 注册一个比赛观众连接，并把清理动作挂在连接的三种终止回调上。
     * <p>
     * 三个回调都必须挂：只挂 {@code onCompletion} 会在对端异常断开（TCP RST、代理超时）时漏清理，
     * 泄漏的 {@link SseEmitter} 会让后续每次广播都对一个死连接执行 {@code send} 并反复抛 IOException。
     * </p>
     *
     * @param contestId 比赛 ID，作为连接的分组维度
     * @param emitter  已交给 Spring MVC 托管的发射器，调用方不要再自行 send
     */
    public void register(Long contestId, SseEmitter emitter) {
        emitters.computeIfAbsent(contestId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(contestId, emitter));
        emitter.onTimeout(() -> remove(contestId, emitter));
        emitter.onError(e -> remove(contestId, emitter));
        log.debug("SSE 连接注册 contestId={}, total={}", contestId, emitters.get(contestId).size());
    }

    /**
     * 向某场比赛的所有在线观众广播一帧榜单数据，事件名固定为 {@code ranking}。
     * <p>
     * 单个连接的发送失败<b>只摘除该连接</b>，不影响同一场比赛的其他观众——
     * 榜单推送是尽力而为的通知语义，某观众断线不应阻塞其余观众的实时性，
     * 真正的数据一致性由数据库快照查询兜底（前端重连时会拉到全量）。
     * </p>
     * 本方法不捕获 {@code RuntimeException}：{@code send} 声明的是 {@link IOException}，
     * 在 Spring 的实现里非法状态（如连接已 complete）同样走 IOException 分支，已被覆盖。
     *
     * @param contestId 目标比赛 ID；无在线连接时直接返回，不做任何事
     * @param data 载荷对象，由 Jackson 序列化为 JSON 后作为 {@code data:} 行写入
     */
    public void broadcast(Long contestId, Object data) {
        List<SseEmitter> list = emitters.get(contestId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name("ranking").data(data));
            } catch (IOException ex) {
                // fail-open：该观众已断开，摘除后继续推给下一个人，不中断整场广播
                remove(contestId, e);
            }
        }
    }

    /**
     * 从指定比赛的连接列表中摘除一个发射器；列表随之变空时连比赛维度的条目一起删除。
     * <p>
     * 不加同步：{@link CopyOnWriteArrayList#remove} 本身线程安全，
     * 「判断空列表后再删 Map 条目」这两步不是原子的，但顶多造成一次空列表残留，
     * 下一次注册时 {@code computeIfAbsent} 会复用它，代价为零——刻意不用 compute 保持这里的简洁。
     * </p>
     */
    private void remove(Long contestId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(contestId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(contestId);
        }
    }
}
