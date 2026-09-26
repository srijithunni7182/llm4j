package io.github.llm4j.getviral.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchToolsTest {

    @Test
    void readPageOnlyReachesThePublicInternet() {
        for (String url : new String[] {
                "http://127.0.0.1/", "http://localhost:80/", "http://169.254.169.254/computeMetadata/v1/",
                "http://10.0.0.8/", "http://192.168.1.1/", "http://172.16.0.1/", "http://[::1]/", "http://[fd00::1]/",
                "http://[::ffff:10.0.0.1]/", "http://100.64.0.1/", "http://0.0.0.0/",
                "file:///etc/passwd", "ftp://example.com/x", "https://example.com:8443/", "https://user:pw@example.com/"}) {
            assertThatThrownBy(() -> ReadPageTool.checkPublic(URI.create(url)))
                    .as(url).isInstanceOf(ReadPageTool.BlockedException.class);
        }
    }

    @Test
    void publicAddressesAreAllowed() throws Exception {
        assertThat(ReadPageTool.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(ReadPageTool.isPublic(InetAddress.getByName("2606:4700::1111"))).isTrue();
    }

    @Test
    void extractsTitleDateAndArticleTextWithoutPageChrome() {
        String html = """
                <html><head><title>Why walking meetings work &amp; when they don&#39;t</title>
                <meta property="article:published_time" content="2026-03-14T09:00:00Z">
                <meta name="description" content="A look at the research on walking meetings.">
                <script>var tracking = "ignore me please, this is not article text at all";</script></head>
                <body><nav><a>Home</a> <a>News</a> <a>Subscribe to our newsletter for more stories today</a></nav>
                <article><h1>Walking meetings</h1>
                <p>Researchers found that people generated more creative ideas while walking than while sitting down.</p>
                <p>Menu</p>
                <p>The effect held whether participants walked indoors on a treadmill or outside in fresh air.</p>
                <p>%s</p></article>
                <footer>Copyright notice and a long list of links that should never be part of the text</footer></body></html>
                """.formatted("Padding sentence that keeps the article long enough to be chosen as the main text. ".repeat(8));
        String out = ReadPageTool.summarise(new ReadPageTool.Page(URI.create("https://example.com/walking"), html));

        assertThat(out).contains("Page: Why walking meetings work & when they don't")
                .contains("Published: 2026-03-14").contains("Summary: A look at the research")
                .contains("more creative ideas while walking").contains("treadmill or outside");
        assertThat(out).doesNotContain("tracking").doesNotContain("newsletter").doesNotContain("Copyright")
                .doesNotContain("Menu");
    }

    @Test
    void readPageRefusesInternalUrlsWithAClearMessage() {
        String out = new ReadPageTool(false, null).execute(Map.of("url", "http://169.254.169.254/latest/meta-data/"));
        assertThat(out).startsWith("Can't read").contains("public internet");
    }

    @Test
    void webSearchNumbersItsSourcesAndSaysWhenItIsOffline() {
        PublicApiTool.clearCache();
        String out = new WebSearchTool(true, null, null, null).execute(Map.of("query", "habits"));
        assertThat(out).startsWith("Web research for \"habits\"")
                .contains("[1] Habit — Wikipedia").contains("https://en.wikipedia.org/wiki/Habit")
                .contains("Google Search needs a Gemini key")
                .endsWith("[source: offline — live web unreachable, results are samples]");
    }

    @Test
    void googleSearchFallsBackToAFlashModelWhenTheStudioModelCantSearch() {
        WebSearchTool.clearGroundedCache();
        assertThat(new WebSearchTool(false, null, "key", "gemini/gemini-3.5-pro").searchModels())
                .containsExactly("gemini-3.5-pro", WebSearchTool.FALLBACK_MODEL);
        assertThat(new WebSearchTool(false, null, "key", WebSearchTool.FALLBACK_MODEL).searchModels())
                .containsExactly(WebSearchTool.FALLBACK_MODEL);
    }
}
