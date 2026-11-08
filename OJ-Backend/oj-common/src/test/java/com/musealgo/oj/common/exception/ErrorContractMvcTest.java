package com.musealgo.oj.common.exception;

import com.musealgo.oj.common.api.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Public MVC error contract, independent of databases and authentication. */
class ErrorContractMvcTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ProbeConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void tearDown() { context.close(); }

    @ParameterizedTest
    @CsvSource({"missing_query,400,REQUIRED_PARAMETER_MISSING", "bad_path_type,400,PARAMETER_TYPE_MISMATCH", "malformed_json,400,REQUEST_BODY_INVALID", "method_not_allowed,405,METHOD_NOT_ALLOWED", "unsupported_media,415,MEDIA_TYPE_UNSUPPORTED", "missing_resource,404,RESOURCE_NOT_FOUND", "bean_validation,400,VALIDATION_FAILED", "rate_limit,429,RATE_LIMITED", "illegal_state,500,INTERNAL_ERROR", "illegal_argument,500,INTERNAL_ERROR", "missing_part,400,REQUIRED_PARAMETER_MISSING", "upload_limit,413,UPLOAD_TOO_LARGE", "method_argument,400,VALIDATION_FAILED", "method_return,500,INTERNAL_ERROR"})
    void returnsClassifiedSafeErrors(String name, int expectedCode, String expectedErrorCode) throws Exception {
        MockHttpServletRequestBuilder request = switch (name) {
            case "missing_query" -> get("/probe/query");
            case "bad_path_type" -> get("/probe/id/not-a-number");
            case "malformed_json" -> post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{");
            case "method_not_allowed" -> delete("/probe/query");
            case "unsupported_media" -> post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("x");
            case "missing_resource" -> get("/not-found-resource");
            case "bean_validation" -> post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}");
            case "rate_limit" -> get("/probe/rate");
            case "illegal_state" -> get("/probe/state");
            case "illegal_argument" -> get("/probe/argument");
            case "missing_part" -> multipart("/probe/upload");
            case "upload_limit" -> get("/probe/upload-limit");
            case "method_argument" -> get("/probe/minimum").param("page", "0");
            case "method_return" -> get("/probe/return");
            default -> throw new IllegalArgumentException(name);
        };
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        System.out.printf("ERROR_PROBE %s http=%d body=%s%n", name, result.getResponse().getStatus(), body);
        assertEquals(200, result.getResponse().getStatus());
        assertTrue(body.contains("\"code\":" + expectedCode), body);
        assertTrue(body.contains("\"errorCode\":\"" + expectedErrorCode + "\""), body);
        if (name.equals("rate_limit")) assertTrue(body.contains("\"retryAfterSeconds\":12"), body);
        if (name.equals("bean_validation")) assertTrue(body.contains("\"fieldErrors\""), body);
        if (name.equals("illegal_state")) assertFalse(body.contains("比赛尚未结束"));
        if (name.equals("method_return")) assertFalse(body.contains("synthetic invalid return"));
    }

    @Test void incompatibleAcceptReturnsEmpty406WithoutSecondConversionFailure() throws Exception {
        var response = mvc.perform(get("/probe/query").param("page", "1").accept(MediaType.IMAGE_PNG))
                .andReturn().getResponse();
        assertEquals(406, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }
    @Configuration
    @EnableWebMvc
    static class ProbeConfig implements WebMvcConfigurer {
        @Bean ProbeController probeController() { return new ProbeController(); }
        @Bean GlobalExceptionHandler handler() { return new GlobalExceptionHandler(); }
        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/audit-nonexistent/");
        }
    }
    record Body(@NotBlank(message = "名称不能为空") String name) {}
    @RestController
    static class ProbeController {
        @GetMapping("/probe/query") Result<?> query(@RequestParam("page") int page) { return Result.success(page); }
        @GetMapping("/probe/id/{id}") Result<?> id(@PathVariable("id") Long id) { return Result.success(id); }
        @PostMapping(value = "/probe/body", consumes = "application/json") Result<?> body(@Valid @RequestBody Body body) { return Result.success(body); }
        @GetMapping("/probe/rate") Result<?> rate() { throw new RateLimitException(12); }
        @GetMapping("/probe/state") Result<?> state() { throw new IllegalStateException("比赛尚未结束"); }
        @GetMapping("/probe/argument") Result<?> argument() { throw new IllegalArgumentException("题目不存在"); }
        @PostMapping("/probe/upload") Result<?> upload(@RequestPart("file") MultipartFile file) { return Result.success(null); }
        @GetMapping("/probe/upload-limit") Result<?> uploadLimit() { throw new MaxUploadSizeExceededException(1024); }
        @GetMapping("/probe/minimum") Result<?> minimum(@RequestParam("page") @Min(1) int page) { return Result.success(page); }
        @GetMapping("/probe/return") @NotBlank(message = "synthetic invalid return") String invalidReturn() { return ""; }
    }
}
