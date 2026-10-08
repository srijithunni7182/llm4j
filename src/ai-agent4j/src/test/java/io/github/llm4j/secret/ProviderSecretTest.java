package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.TextToSpeechRequest;
import io.github.llm4j.model.TranscriptionRequest;
import io.github.llm4j.model.TranslationRequest;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.sarvam.SarvamAudioProvider;
import io.github.llm4j.provider.sarvam.SarvamChatProvider;
import io.github.llm4j.provider.sarvam.SarvamTextProvider;
import io.github.llm4j.provider.sarvam.SarvamTextToSpeechProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every provider fetches its key from the secret store <b>for each request</b>, sends it only in its header, refuses hosts the secret is not
 * bound to before anything is sent, and never lets the key reach an error message. HTTP goes to a local mock server: no network, no real keys.
 */
class ProviderSecretTest {

    @TempDir
    Path dir;

    private MockWebServer server;
    private InMemorySecretStore store;

    /** One provider: which header carries its key and how to make a call (the response is irrelevant: the request is what is checked). */
    private record Case(String name, String header, Function<LLMConfig, Runnable> call) { }

    private Case[] cases() {
        return new Case[] {
                new Case("google", "x-goog-api-key", c -> () -> new GoogleProvider(c).chat(LLMRequest.builder().addUserMessage("hi").build())),
                new Case("anthropic", "x-api-key", c -> () -> new AnthropicProvider(c).chat(LLMRequest.builder().addUserMessage("hi").build())),
                new Case("sarvam-chat", "api-subscription-key", c -> () -> new SarvamChatProvider(c).chat(LLMRequest.builder().addUserMessage("hi").build())),
                new Case("sarvam-text", "api-subscription-key", c -> () -> new SarvamTextProvider(c)
                        .translate(TranslationRequest.builder().text("hi").targetLanguageCode("hi-IN").build())),
                new Case("sarvam-tts", "api-subscription-key", c -> () -> new SarvamTextToSpeechProvider(c)
                        .generateSpeech(TextToSpeechRequest.builder().text("hi").targetLanguageCode("hi-IN").build())),
                new Case("sarvam-audio", "api-subscription-key", c -> () -> {
                    try {
                        File audio = Files.write(dir.resolve("a.wav"), new byte[] {1, 2, 3}).toFile();
                        new SarvamAudioProvider(c).transcribe(audio, TranscriptionRequest.builder().build());
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }),
                new Case("gemini-embedding", "x-goog-api-key", c -> () -> new GeminiEmbeddingProvider(c).embed("hi")),
        };
    }

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        store = new InMemorySecretStore();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private LLMConfig config(SecretRef ref) {
        return LLMConfig.builder().apiKey(ref).baseUrl(server.url("/").toString().replaceAll("/$", "")).defaultModel("m")
                .retryPolicy(io.github.llm4j.config.RetryPolicy.builder().maxRetries(0).build()).build();
    }

    /** Makes the call; whatever the provider makes of the (empty) response is not the point. */
    private void callQuietly(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // a parse failure on the canned response
        }
    }

    private RecordedRequest take() throws Exception {
        RecordedRequest r = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(r, "no request reached the server");
        return r;
    }

    @Test
    void theKeyIsFetchedForEachRequestSoRotationNeedsNoRebuild() throws Exception {
        for (Case c : cases()) {
            store.put("k", FakeKeys.ONE);
            Runnable call = c.call().apply(config(SecretRef.of(store, "k")));
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(call);
            RecordedRequest first = take();
            assertEquals(FakeKeys.ONE, first.getHeader(c.header()), c.name());

            store.put("k", FakeKeys.TWO); // rotate while the provider object lives on
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(call);
            RecordedRequest second = take();
            assertEquals(FakeKeys.TWO, second.getHeader(c.header()), c.name() + ": the second request must carry the rotated key");
        }
    }

    @Test
    void theKeyNeverTravelsInTheUrl() throws Exception {
        for (Case c : cases()) {
            store.put("k", FakeKeys.ONE);
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(c.call().apply(config(SecretRef.of(store, "k"))));
            RecordedRequest r = take();
            assertFalse(r.getRequestUrl().toString().contains(FakeKeys.ONE), c.name() + ": " + r.getRequestUrl());
            assertFalse(r.getPath().toLowerCase().contains("key="), c.name() + ": " + r.getPath());
        }
    }

    @Test
    void aSecretBoundToOtherHostsIsRefusedBeforeAnythingIsSent() {
        for (Case c : cases()) {
            store.put("k", FakeKeys.ONE, SecretMetadata.allowing("api.example.com"));
            Runnable call = c.call().apply(config(SecretRef.of(store, "k")));
            RuntimeException e = assertThrows(RuntimeException.class, call::run, c.name());
            Throwable root = e;
            while (root instanceof RuntimeException && root.getCause() != null) root = root.getCause();
            assertTrue(e instanceof AuthenticationException || root instanceof AuthenticationException || e.getCause() instanceof AuthenticationException,
                    c.name() + ": " + e);
            assertFalse(String.valueOf(e.getMessage()).contains(FakeKeys.ONE), c.name());
        }
        assertEquals(0, server.getRequestCount(), "nothing was sent to a host the secret may not go to");
    }

    @Test
    void aSecretBoundToTheRealHostIsAccepted() throws Exception {
        for (Case c : cases()) {
            store.put("k", FakeKeys.ONE, SecretMetadata.allowing(server.getHostName()));
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(c.call().apply(config(SecretRef.of(store, "k"))));
            assertEquals(FakeKeys.ONE, take().getHeader(c.header()), c.name());
        }
    }

    @Test
    void aMissingSecretIsAnAuthenticationErrorThatNamesTheSecret() {
        for (Case c : cases()) {
            LLMConfig config = config(SecretRef.of(store, "not-there"));
            RuntimeException e = assertThrows(RuntimeException.class, () -> c.call().apply(config).run(), c.name());
            Throwable t = e;
            boolean found = false;
            while (t != null) {
                if (t instanceof AuthenticationException && String.valueOf(t.getMessage()).contains("not-there")) found = true;
                t = t.getCause();
            }
            assertTrue(found, c.name() + ": " + e);
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void theStringOverloadBehavesAsBefore() throws Exception {
        for (Case c : cases()) {
            LLMConfig config = LLMConfig.builder().apiKey(FakeKeys.ONE).baseUrl(server.url("/").toString().replaceAll("/$", "")).defaultModel("m").build();
            server.enqueue(new MockResponse().setBody("{}"));
            callQuietly(c.call().apply(config));
            assertEquals(FakeKeys.ONE, take().getHeader(c.header()), c.name());
        }
    }

    @Test
    void aProviderThatEchoesTheKeyInAnErrorDoesNotLeakIt() {
        List<String> chatCases = List.of("google", "anthropic", "sarvam-chat");
        for (Case c : cases()) {
            if (!chatCases.contains(c.name())) continue;
            store.put("k", FakeKeys.ONE);
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid key " + FakeKeys.ONE + " for this project\"}}"));
            RuntimeException e = assertThrows(RuntimeException.class, c.call().apply(config(SecretRef.of(store, "k")))::run, c.name());
            assertFalse(allText(e).contains(FakeKeys.ONE), c.name() + ": " + allText(e));
            assertTrue(allText(e).contains("***"), c.name() + " should show the scrubbed form: " + allText(e));
        }
    }

    private static String allText(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t).append(' ');
            if (t instanceof io.github.llm4j.exception.LLMException l && l.getResponseBody() != null) sb.append(l.getResponseBody()).append(' ');
        }
        return sb.toString();
    }

    @Test
    void theEmbeddingProviderNoLongerPutsTheKeyInAnErrorMessageOrTheUrl() throws Exception {
        store.put("k", FakeKeys.ONE);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> new GeminiEmbeddingProvider(config(SecretRef.of(store, "k"))).embed("hi"));
        assertFalse(allText(e).contains(FakeKeys.ONE), allText(e));
        RecordedRequest r = take();
        assertFalse(r.getRequestUrl().toString().contains(FakeKeys.ONE));
        assertEquals(FakeKeys.ONE, r.getHeader("x-goog-api-key"));
    }
}
