package top.everblue.oj.ai.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.ai.entity.PromptTemplate;
import top.everblue.oj.ai.mapper.PromptTemplateMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词模板注册表（O5：PromptRegistry）。
 * <p>
 * 模板按 id + 版本管理：版本递增不影响旧链路，回滚 = 指定低版本。
 * 当前用法：{@code register("tutor", 1, template)} → {@code render("tutor", args...)}。
 * </p>
 */
@Component
public class PromptRegistry {

    private final Map<String, Map<Integer, String>> templates = new ConcurrentHashMap<>();
    private final PromptTemplateMapper promptTemplateMapper;
    private final Map<String, CachedTemplate> activeCache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MS = 5_000L;

    public PromptRegistry(PromptTemplateMapper promptTemplateMapper) {
        this.promptTemplateMapper = promptTemplateMapper;
    }

    /** 注册模板；同 id 更高版本覆盖「当前版本」 */
    public void register(String id, int version, String template) {
        templates.computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                .put(version, template);
    }

    /** 当前（最高）版本号；未注册返回 0 */
    public int currentVersion(String id) {
        Map<Integer, String> versions = templates.get(id);
        return versions == null ? 0 : versions.keySet().stream().max(Integer::compareTo).orElse(0);
    }

    /** 渲染当前版本模板；未注册返回 null。无参数时原样返回（模板文本里的 % 不应被当作格式说明符） */
    public String render(String id, Object... args) {
        String template = get(id);
        if (template == null) {
            return null;
        }
        return args.length == 0 ? template : template.formatted(args);
    }

    /** 取当前版本模板文本 */
    public String get(String id) {
        // DB active 版本优先（E2：管理台可切换）
        CachedTemplate cached = activeCache.get(id);
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.content;
        }
        PromptTemplate active = promptTemplateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getPromptKey, id)
                        .eq(PromptTemplate::getIsActive, 1)
                        .last("LIMIT 1"));
        if (active != null) {
            activeCache.put(id, new CachedTemplate(active.getContent(),
                    System.currentTimeMillis() + CACHE_TTL_MS));
            return active.getContent();
        }
        // 回落到代码注册的模板
        Map<Integer, String> versions = templates.get(id);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        int latest = versions.keySet().stream().max(Integer::compareTo).orElse(0);
        String content = versions.get(latest);
        activeCache.put(id, new CachedTemplate(content, System.currentTimeMillis() + CACHE_TTL_MS));
        return content;
    }

    /** 管理台：列出某模板的全部版本（含 DB 与代码注册） */
    public List<PromptTemplate> listDbVersions(String promptKey) {
        return promptTemplateMapper.selectList(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(promptKey != null, PromptTemplate::getPromptKey, promptKey)
                        .orderByAsc(PromptTemplate::getPromptKey)
                        .orderByDesc(PromptTemplate::getVersion));
    }

    /** 管理台：新建版本（版本号 = 当前最高 + 1，自动激活） */
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate createVersion(String promptKey, String content) {
        promptTemplateMapper.lockPromptKey(promptKey);
        List<PromptTemplate> versions = promptTemplateMapper.selectByPromptKeyForUpdate(promptKey);
        Integer maxVersion = versions
                .stream().map(PromptTemplate::getVersion).max(Integer::compareTo).orElse(0);
        PromptTemplate template = new PromptTemplate();
        template.setPromptKey(promptKey);
        template.setVersion(maxVersion + 1);
        template.setContent(content);
        template.setIsActive(1);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        promptTemplateMapper.insert(template);
        // 同 key 其他版本全部置非激活
        for (PromptTemplate version : versions) {
            if (Integer.valueOf(1).equals(version.getIsActive())) {
                version.setIsActive(0);
                promptTemplateMapper.updateById(version);
            }
        }
        activeCache.remove(promptKey);
        return template;
    }

    /** 管理台：激活指定版本 */
    @Transactional(rollbackFor = Exception.class)
    public void activateVersion(Long id) {
        PromptTemplate target = promptTemplateMapper.selectById(id);
        if (target == null) {
            throw new IllegalArgumentException("模板不存在: " + id);
        }
        promptTemplateMapper.lockPromptKey(target.getPromptKey());
        List<PromptTemplate> versions = promptTemplateMapper
                .selectByPromptKeyForUpdate(target.getPromptKey());
        for (PromptTemplate version : versions) {
            if (version.getId().equals(id) || !Integer.valueOf(1).equals(version.getIsActive())) {
                continue;
            }
            version.setIsActive(0);
            promptTemplateMapper.updateById(version);
        }
        target.setIsActive(1);
        promptTemplateMapper.updateById(target);
        activeCache.remove(target.getPromptKey());
    }

    private record CachedTemplate(String content, long expireAt) {
    }
}
