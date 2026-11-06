package io.github.llm4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaCapabilities;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.Message;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/specs/31-multimodal-input R10 and R12: media the model cannot take is refused before any
 * call; the wrappers pass the capability and the media on and never drop it.
 */
class MediaClientGuardTest {

    private static final MediaPolicy POLICY = MediaPolicy.defaults();

    /** Takes what it is told it takes, and records every request it actually receives. */
    private static final class Fake implements LLMProvider {
        final MediaCapabilities caps;
        final List<LLMRequest> seen = new ArrayList<>();

        Fake(MediaCapabilities caps) {
            this.caps = caps;
        }

        @Override
        public LLMResponse chat(LLMRequest request) {
            seen.add(request);
            return LLMResponse.builder().content("ok").model("m").tokenUsage(1, 1, 2).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest request) {
            return Stream.of(chat(request));
        }

        @Override
        public String getProviderName() {
            return "fake";
        }

        @Override
        public void validate() {}

        @Override
        public MediaCapabilities mediaCapabilities(String model) {
            return caps;
        }
    }

    private static LLMRequest withPhoto() {
        Media photo = Media.of(MediaKind.IMAGE, tiny(), "a.png", POLICY);
        return LLMRequest.builder().addMessage(Message.user("look", List.of(photo))).build();
    }

    private static byte[] tiny() {
        byte[] png = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, png, 0, magic.length);
        return png;
    }

    @Test
    void aClientTakesNoMediaUnlessItSaysSo() {
        LLMClient plain =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        return null;
                    }

                    @Override
                    public Stream<LLMResponse> chatStream(LLMRequest request) {
                        return Stream.empty();
                    }
                };

        assertThat(plain.mediaCapabilities(null)).isEqualTo(MediaCapabilities.NONE);
    }

    @Test
    void refusesMediaTheProviderCannotTakeAndSendsNothing() {
        Fake provider = new Fake(MediaCapabilities.NONE);
        DefaultLLMClient client = new DefaultLLMClient(provider);

        assertThatThrownBy(() -> client.chat(withPhoto()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("fake")
                .hasMessageContaining("does not accept image");
        assertThat(provider.seen).isEmpty();
    }

    @Test
    void refusesOnAStreamedCallToo() {
        Fake provider = new Fake(MediaCapabilities.NONE);
        DefaultLLMClient client = new DefaultLLMClient(provider);

        assertThatThrownBy(() -> client.chatStream(withPhoto()).toList())
                .isInstanceOf(InvalidRequestException.class);
        assertThat(provider.seen).isEmpty();
    }

    @Test
    void sendsMediaTheProviderTakes() {
        Fake provider = new Fake(MediaCapabilities.of(MediaKind.IMAGE));

        new DefaultLLMClient(provider).chat(withPhoto());

        assertThat(provider.seen).hasSize(1);
        assertThat(provider.seen.get(0).getMessages().get(0).getMedia()).hasSize(1);
    }

    @Test
    void textOnlyRequestsAreNeverAffected() {
        Fake provider = new Fake(MediaCapabilities.NONE);

        new DefaultLLMClient(provider).chat(LLMRequest.builder().addUserMessage("hi").build());

        assertThat(provider.seen).hasSize(1);
    }

    @Test
    void theClientReportsTheProvidersCapabilities() {
        MediaCapabilities caps = MediaCapabilities.of(MediaKind.IMAGE, MediaKind.AUDIO);

        assertThat(new DefaultLLMClient(new Fake(caps)).mediaCapabilities("any")).isEqualTo(caps);
    }

    @Test
    void theBudgetedClientPassesTheCapabilityAndTheMediaOn() {
        Fake provider = new Fake(MediaCapabilities.of(MediaKind.IMAGE));
        BudgetedLLMClient client =
                BudgetedLLMClient.builder(new DefaultLLMClient(provider))
                        .budget(Budget.builder().tokens(100_000).build())
                        .build();

        assertThat(client.mediaCapabilities(null)).isEqualTo(MediaCapabilities.of(MediaKind.IMAGE));
        client.chat(withPhoto());
        assertThat(provider.seen.get(0).getMessages().get(0).getMedia()).hasSize(1);
    }

    @Test
    void theMaskingClientPassesTheCapabilityAndKeepsTheMedia() {
        Fake provider = new Fake(MediaCapabilities.of(MediaKind.IMAGE));
        // masking refuses media unless told it is acceptable (MaskingMediaTest); here it is
        MaskingLLMClient client =
                new MaskingLLMClient(new DefaultLLMClient(provider)).allowUnscannedMedia();

        assertThat(client.mediaCapabilities(null)).isEqualTo(MediaCapabilities.of(MediaKind.IMAGE));
        client.chat(withPhoto());
        assertThat(provider.seen.get(0).getMessages().get(0).getMedia()).hasSize(1);
        assertThat(provider.seen.get(0).getMessages().get(0).getContent()).isEqualTo("look");
    }

    @Test
    void theRouterOnlyClaimsWhatEveryClientBehindItTakes() {
        LLMClient both =
                new DefaultLLMClient(
                        new Fake(MediaCapabilities.of(MediaKind.IMAGE, MediaKind.AUDIO)));
        LLMClient imagesOnly =
                new DefaultLLMClient(new Fake(MediaCapabilities.of(MediaKind.IMAGE)));
        RoutingLLMClient router =
                RoutingLLMClient.builder()
                        .addClient(ProviderTier.FAST_CHEAP, both)
                        .addClient(ProviderTier.REASONING, imagesOnly)
                        .build();

        assertThat(router.mediaCapabilities(null)).isEqualTo(MediaCapabilities.of(MediaKind.IMAGE));
    }
}
