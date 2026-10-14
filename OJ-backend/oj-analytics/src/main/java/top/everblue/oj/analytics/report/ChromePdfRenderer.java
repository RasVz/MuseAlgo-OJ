package top.everblue.oj.analytics.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 browserless/chrome 的 PDF 渲染实现。
 * <p>
 * 调用 browserless 的 {@code POST /pdf} 端点：
 * <ul>
 *   <li>body.html：完整 HTML</li>
 *   <li>options：A4 + 打印背景 + 页边距</li>
 *   <li>gotoOptions.waitUntil=networkidle0：等待文档资源稳定</li>
 *   <li>waitForSelector：等待模板内 SVG 图表绘制后露出的
 *       {@code #__charts_ready__} 标记元素，保证截图时图表已就绪</li>
 * </ul>
 */
@Slf4j
@Component
public class ChromePdfRenderer implements ReportRenderer {

    /** HTML 中图表全部渲染完成后才可见的标记元素 id，与 weekly-report.ftl 中脚本约定一致 */
    private static final String READY_SELECTOR = "#__charts_ready__";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String chromiumUrl;

    public ChromePdfRenderer(@Value("${oj.report.chromium-url:http://127.0.0.1:3000}") String chromiumUrl) {
        this.chromiumUrl = chromiumUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public byte[] renderPdf(String html) {
        String payload = buildPayload(html);

        Request request = new Request.Builder()
                .url(chromiumUrl + "/pdf")
                .post(RequestBody.create(payload, JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String err = response.body() != null ? response.body().string() : "empty body";
                throw new ReportRenderException("Chromium 渲染失败 HTTP " + response.code() + ": " + err);
            }
            return response.body().bytes();
        } catch (IOException e) {
            throw new ReportRenderException("Chromium 服务不可达: " + chromiumUrl, e);
        }
    }

    private String buildPayload(String html) {
        ObjectNode root = JSON.createObjectNode();
        root.put("html", html);

        ObjectNode options = root.putObject("options");
        options.put("format", "A4");
        options.put("printBackground", true);
        ObjectNode margin = options.putObject("margin");
        margin.put("top", "10mm");
        margin.put("bottom", "10mm");
        margin.put("left", "12mm");
        margin.put("right", "12mm");

        ObjectNode gotoOptions = root.putObject("gotoOptions");
        gotoOptions.put("waitUntil", "networkidle0");
        gotoOptions.put("timeout", 30000);

        ObjectNode waitFor = root.putObject("waitForSelector");
        waitFor.put("selector", READY_SELECTOR);
        waitFor.put("visible", true);
        waitFor.put("timeout", 20000);

        return root.toString();
    }
}
