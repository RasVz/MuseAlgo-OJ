package top.everblue.oj.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守卫 — 业务模块解耦硬约束（评审 O10）。
 * <p>
 * 规则：业务模块（judge/analytics/teaching/contest/community/problem/ai）
 * 不得依赖<b>其他</b>业务模块（含基础域 system）的 {@code mapper} / {@code service} / {@code entity} 包，
 * 跨模块访问只允许走 oj-api 契约（oj-common / oj-framework / oj-api 除外）。
 * </p>
 * <p>
 * 白名单（基础设施/认证上下文，非业务数据访问）：
 * <ul>
 *   <li>{@code top.everblue.oj.system.entity.User} — Spring Security 认证主体判断（principal instanceof User）</li>
 * </ul>
 * </p>
 * <p>
 * 历史：FileService（对象存储）曾为白名单特例，2026-08-15 已下沉 oj-common 基座层，白名单条目随之删除。
 * </p>
 */
@AnalyzeClasses(packages = "top.everblue.oj",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ModuleDependencyArchTest {

    /** 业务模块（被约束方） */
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "judge", "analytics", "teaching", "contest", "community", "problem", "ai"
    );

    /** 依赖目标集合：全部业务模块 + 基础域 system */
    private static final Set<String> ALL_OWNED_MODULES = Set.of(
            "judge", "analytics", "teaching", "contest", "community", "problem", "ai", "system"
    );

    /** 禁止跨模块访问的实现层包 */
    private static final Set<String> IMPL_PACKAGES = Set.of("mapper", "service", "entity");

    /** 白名单：认证主体实体（基础设施工具已下沉 oj-common，不再需要白名单） */
    private static final Set<String> WHITELISTED_TARGETS = Set.of(
            "top.everblue.oj.system.entity.User"
    );

    @ArchTest
    static final ArchRule BUSINESS_MODULES_ACCESS_ONLY_VIA_CONTRACT = noClasses()
            .that().resideInAnyPackage(BUSINESS_MODULES.stream().map(m -> "..oj." + m + "..").toArray(String[]::new))
            .should(onlyContractDependencies())
            .as("业务模块不得依赖其他业务模块（含 system）的 mapper/service/entity，跨模块访问只走 oj-api 契约");

    private static ArchCondition<JavaClass> onlyContractDependencies() {
        return new ArchCondition<>("只通过 oj-api 契约访问其他模块的实现层") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source);
                if (sourceModule == null || !BUSINESS_MODULES.contains(sourceModule)) {
                    return;
                }
                for (JavaClass target : targets(source)) {
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(sourceModule)) {
                        continue; // 基础层契约或同模块内部，放行
                    }
                    if (!ALL_OWNED_MODULES.contains(targetModule)) {
                        continue; // common/framework/api 等基座层，放行
                    }
                    if (isImplLayerPackage(target) && !WHITELISTED_TARGETS.contains(target.getName())) {
                        events.add(SimpleConditionEvent.violated(source, String.format(
                                "%s 依赖了 %s —— 跨模块访问 %s.%s 实现层，应改走 oj-api 契约",
                                source.getName(), target.getName(), targetModule, layerOf(target))));
                    }
                }
            }
        };
    }

    /** 提取类所属模块（top.everblue.oj.<module>.<...>），基座层类返回 null */
    private static String moduleOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        if (!pkg.startsWith("top.everblue.oj.")) {
            return null;
        }
        String rest = pkg.substring("top.everblue.oj.".length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static boolean isImplLayerPackage(JavaClass javaClass) {
        return IMPL_PACKAGES.contains(layerOf(javaClass));
    }

    private static String layerOf(JavaClass javaClass) {
        String pkg = javaClass.getPackageName();
        String rest = pkg.substring("top.everblue.oj.".length());
        int firstDot = rest.indexOf('.');
        if (firstDot < 0) {
            return "";
        }
        int secondDot = rest.indexOf('.', firstDot + 1);
        return secondDot < 0 ? rest.substring(firstDot + 1) : rest.substring(firstDot + 1, secondDot);
    }

    private static List<JavaClass> targets(JavaClass source) {
        return source.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass)
                .collect(Collectors.toList());
    }
}
