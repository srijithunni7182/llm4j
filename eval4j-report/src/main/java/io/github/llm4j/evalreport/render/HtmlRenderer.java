package io.github.llm4j.evalreport.render;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.evalreport.model.ReportModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The interactive edition: one self-contained HTML file. The model is embedded as inert JSON and
 * drawn by an inline script; there is no external script, stylesheet, font or image, and the page
 * makes no network request.
 */
public final class HtmlRenderer {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private HtmlRenderer() {}

    public static String render(ReportModel model) {
        try {
            String json = DataEmbed.escape(MAPPER.writeValueAsString(model));
            String title =
                    "eval4j report"
                            + (model.meta().project() == null
                                    ? ""
                                    : " \u00b7 " + Escape.html(model.meta().project()));
            return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<meta name=\"generator\" content=\"eval4j-report\">"
                    + "<title>"
                    + title
                    + "</title>\n<style>\n"
                    + resource("dashboard.css")
                    + "</style></head>\n"
                    + "<body>\n<div class=\"shell\"><aside class=\"side\" id=\"side\" aria-label=\"Navigation\"></aside>"
                    + "<div class=\"col\"><header class=\"top\">"
                    + "<button class=\"iconbtn menu\" id=\"menu\" type=\"button\" aria-label=\"Open navigation\">\u2630</button>"
                    + "<nav class=\"crumbs\" id=\"crumbs\" aria-label=\"Breadcrumb\"></nav>"
                    + "<span class=\"chip mono\" id=\"chip\"></span>"
                    + "<button class=\"iconbtn\" id=\"theme\" type=\"button\" aria-label=\"Toggle light and dark theme\">\u25d0</button></header>\n"
                    + "<main id=\"view\" tabindex=\"-1\"></main>\n"
                    + "<footer class=\"brandfoot\" style=\"max-width:1240px;margin:0 auto;padding-inline:24px\"><p>Free, open-source evaluation for AI agents, from <b>llm4j</b>. "
                    + "This report is one self-contained file: no account, no server, no telemetry.</p>"
                    + "<div class=\"eco\"><span>ai-agent4j</span><span class=\"me\">eval4j</span><span>loom</span><span>engram</span><span>tantrik</span></div></footer>"
                    + "</div></div>\n"
                    + "<div class=\"scrim\" id=\"scrim\" hidden></div><aside class=\"drawer\" id=\"drawer\" hidden aria-label=\"Detail\" aria-hidden=\"true\" tabindex=\"-1\"></aside>\n"
                    + "<script type=\"application/json\" id=\"eval4j-data\">"
                    + json
                    + "</script>\n"
                    + "<script>\n"
                    + resource("dashboard.js")
                    + "</script>\n</body></html>\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = HtmlRenderer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
