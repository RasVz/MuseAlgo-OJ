package top.everblue.oj.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import top.everblue.oj.teaching.SlidevBuilderService;
import top.everblue.oj.teaching.SlidevPathProvider;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.common.exception.ForbiddenException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlidevBuildBoundaryTest {
    @TempDir Path dir;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void studentCannotStartBuild() {
        var builder = new SlidevBuilderService(mock(SlidevPathProvider.class));
        assertThrows(ForbiddenException.class, () -> builder.buildPresentation(new MockMultipartFile("file", "a.md", "text/plain", "# hi".getBytes())));
    }
    @Test void stalledProcessIsTerminatedAndTemporaryMarkdownIsRemoved() throws Exception {
        User teacher = new User(); teacher.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(teacher, null, List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        Path cli = dir.resolve("node_modules/.bin/slidev"); Files.createDirectories(cli.getParent());
        Files.writeString(cli, "#!/bin/sh\nexec sleep 20\n"); assertTrue(cli.toFile().setExecutable(true));
        var paths = mock(SlidevPathProvider.class); when(paths.workerDir()).thenReturn(dir); when(paths.outputRoot()).thenReturn(dir.resolve("out")); when(paths.publicPath()).thenReturn("/coursewares/");
        var builder = new SlidevBuilderService(paths); ReflectionTestUtils.setField(builder, "buildTimeoutSeconds", 1L);
        assertTimeout(java.time.Duration.ofSeconds(5), () -> assertThrows(IllegalStateException.class,
                () -> builder.buildPresentation(new MockMultipartFile("file", "a.md", "text/plain", "# hi".getBytes()))));
        try (var files = Files.list(dir)) { assertTrue(files.noneMatch(f -> f.toString().endsWith(".md"))); }
    }
}
