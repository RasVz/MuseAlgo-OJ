package top.everblue.oj.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadResourceConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Configuration
    @EnableWebMvc
    static class WebConfiguration {
    }

    @Test
    void servesFirstUploadWhenDirectoryDidNotExistAtStartup() throws Exception {
        Path uploadDirectory = temporaryDirectory.resolve("uploads");
        try (GenericWebApplicationContext context = new GenericWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            new AnnotatedBeanDefinitionReader(context).register(WebConfiguration.class);
            context.registerBeanDefinition("uploadResourceConfig",
                    BeanDefinitionBuilder.genericBeanDefinition(UploadResourceConfig.class)
                            .addConstructorArgValue(uploadDirectory.toString()).getBeanDefinition());
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            Path image = uploadDirectory.resolve("post-images/first.png");
            Files.createDirectories(image.getParent());
            byte[] imageBytes = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
            Files.write(image, imageBytes);

            mvc.perform(get("/uploads/post-images/first.png"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(imageBytes));
        }
    }
}
