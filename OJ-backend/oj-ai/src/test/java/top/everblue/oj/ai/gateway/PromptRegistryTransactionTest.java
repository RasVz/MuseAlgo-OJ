package top.everblue.oj.ai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.ai.entity.PromptTemplate;
import top.everblue.oj.ai.mapper.PromptTemplateMapper;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptRegistryTransactionTest {

    @Test
    void promptMutationsAreTransactional() throws Exception {
        assertTransactional("createVersion", String.class, String.class);
        assertTransactional("activateVersion", Long.class);
    }

    @Test
    void createVersionLocksExistingVersionsBeforeChoosingNextVersion() {
        PromptTemplateMapper mapper = mock(PromptTemplateMapper.class);
        PromptTemplate existing = new PromptTemplate();
        existing.setVersion(3);
        when(mapper.selectByPromptKeyForUpdate("tutor")).thenReturn(List.of(existing));
        PromptRegistry registry = new PromptRegistry(mapper);

        PromptTemplate created = registry.createVersion("tutor", "new prompt");

        assertEquals(4, created.getVersion());
        var order = inOrder(mapper);
        order.verify(mapper).lockPromptKey("tutor");
        order.verify(mapper).selectByPromptKeyForUpdate("tutor");
        verify(mapper).selectByPromptKeyForUpdate("tutor");
        verify(mapper).insert(any(PromptTemplate.class));
    }

    @Test
    void activateVersionLocksSiblingVersionsBeforeSwitchingActiveTemplate() {
        PromptTemplateMapper mapper = mock(PromptTemplateMapper.class);
        PromptTemplate target = template(2L, "tutor", 2, 0);
        PromptTemplate active = template(1L, "tutor", 1, 1);
        when(mapper.selectById(2L)).thenReturn(target);
        when(mapper.selectByPromptKeyForUpdate("tutor")).thenReturn(List.of(target, active));
        PromptRegistry registry = new PromptRegistry(mapper);

        registry.activateVersion(2L);

        verify(mapper).lockPromptKey("tutor");
        verify(mapper).selectByPromptKeyForUpdate("tutor");
        assertEquals(0, active.getIsActive());
        assertEquals(1, target.getIsActive());
        verify(mapper).updateById(active);
        verify(mapper).updateById(target);
    }

    private static PromptTemplate template(Long id, String key, int version, int active) {
        PromptTemplate template = new PromptTemplate();
        template.setId(id);
        template.setPromptKey(key);
        template.setVersion(version);
        template.setIsActive(active);
        return template;
    }

    private static void assertTransactional(String name, Class<?>... parameterTypes) throws Exception {
        Method method = PromptRegistry.class.getMethod(name, parameterTypes);
        assertNotNull(method.getAnnotation(Transactional.class));
    }
}
