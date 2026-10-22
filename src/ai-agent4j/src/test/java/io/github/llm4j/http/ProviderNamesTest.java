package io.github.llm4j.http;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.Request;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Rate-limit reports name the vendor from the host (spec 08 §3.3). */
class ProviderNamesTest {

    @ParameterizedTest
    @CsvSource({
        "https://api.openai.com/v1/chat/completions,openai",
        "https://api.x.ai/v1/chat/completions,xai",
        "https://api.moonshot.ai/v1/chat/completions,kimi",
        "https://api.kimi.ai/v1/chat/completions,kimi",
        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions,qwen",
        "https://ws1.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions,qwen",
        "https://api.perplexity.ai/v1/agent,perplexity",
        "https://api.anthropic.com/v1/messages,anthropic",
        "https://generativelanguage.googleapis.com/v1beta/models,google",
        "https://api.sarvam.ai/v1/chat/completions,sarvam",
        "https://llm.internal.example/v1/chat/completions,llm.internal.example"
    })
    void hostsMapToTheirVendor(String url, String expected) {
        assertThat(HttpClientWrapper.providerOf(new Request.Builder().url(url).build()))
                .isEqualTo(expected);
    }
}
