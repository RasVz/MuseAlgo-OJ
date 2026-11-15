package com.musealgo.oj.community.query;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.mapper.PostMapper;
import com.musealgo.oj.community.service.PostHotService;
import com.musealgo.oj.community.service.PostService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostQueryServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 生成列名需要表元数据，单测没有启动 MyBatis 上下文。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Post.class);
    }

    @Test
    void authoredByFiltersClassAndDiscussionExcludesClassType() {
        PostMapper mapper = mock(PostMapper.class);
        PostService posts = mock(PostService.class);
        PostHotService hotPosts = mock(PostHotService.class);
        UserQuery users = mock(UserQuery.class);
        when(users.findDisplayInfo(7L)).thenReturn(Optional.empty());
        when(hotPosts.getPostStatsBatch(anyList())).thenReturn(Map.of());
        var service = new PostQueryServiceImpl(mapper, posts, hotPosts, users, null, null, null, null);

        service.authoredBy(7L, 1, 10, "discussion", 9L);

        ArgumentCaptor<Wrapper<Post>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(posts).page(any(Page.class), captor.capture());
        LambdaQueryWrapper<Post> query = (LambdaQueryWrapper<Post>) captor.getValue();

        // 参数值在生成 SQL 片段时才登记，先取 SQL 再检查参数表。
        String segment = query.getSqlSegment();
        assertTrue(segment.contains("class_id"), "过滤条件应落在 class_id 列");
        assertTrue(query.getParamNameValuePairs().containsValue(9L), "应按 classId 过滤");
        assertFalse(query.getParamNameValuePairs().containsValue(3), "讨论贴不应包含 type=3");
    }
}
