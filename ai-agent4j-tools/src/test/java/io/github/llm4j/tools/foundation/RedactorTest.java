package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.tools.support.Fuzz;
import io.github.llm4j.tools.Redactor;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    @Tag("V1.5")
    void removesTheSecretInEveryFormItCanAppearIn() {
        String secret = "s3cr3t/Value+=&1";
        Redactor r = new Redactor(List.of(secret));
        String urlEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString(("user:" + secret).getBytes(StandardCharsets.UTF_8));

        String out = r.scrub("a " + secret + " b " + urlEncoded + " c Basic " + basic);

        assertThat(out).doesNotContain(secret).doesNotContain(urlEncoded).doesNotContain(basic.substring(8, basic.length() - 4));
        assertThat(out).contains("***").startsWith("a *** b");
    }

    @Test
    @Tag("V1.5")
    void leavesTextWithoutSecretsAlone() {
        assertThat(new Redactor(List.of("hunter2-long")).scrub("nothing to see")).isEqualTo("nothing to see");
        assertThat(Redactor.NONE.scrub("anything")).isEqualTo("anything");
        assertThat(new Redactor(List.of("abc")).scrub("abc stays")).as("too short to scrub safely").isEqualTo("abc stays");
    }

    @Test
    @Tag("F4")
    void generatedSecretsNeverSurviveInAnyForm() {
        Fuzz.run("redactor", random -> {
            String secret = randomSecret(random);
            Redactor r = new Redactor(List.of(secret));
            byte[] s = secret.getBytes(StandardCharsets.UTF_8);

            // The part of a Base64 encoding that belongs to the secret alone is what two encodings share when
            // only the bytes around the secret differ (same lengths, so the same alignment).
            int lead = random.nextInt(3);
            byte[] before = randomBytes(random, lead);
            byte[] after = randomBytes(random, random.nextInt(5));
            String enc1 = Base64.getEncoder().encodeToString(concat(before, s, after));
            String enc2 = Base64.getEncoder().encodeToString(concat(flip(before), s, flip(after)));
            String stable = longestCommonSubstring(enc1, enc2);

            String text = randomText(random) + secret + randomText(random) + " "
                    + URLEncoder.encode(secret, StandardCharsets.UTF_8) + " Basic " + enc1;
            String out = r.scrub(text);

            assertThat(out).doesNotContain(secret);
            assertThat(out).doesNotContain(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            if (stable.length() >= 8) assertThat(out).as("stable Base64 of the secret in " + enc1).doesNotContain(stable);
        });
    }

    @Test
    @Tag("F4")
    void generatedTextWithoutSecretsIsUnchanged() {
        Fuzz.run("redactor-clean", random -> {
            String secret = "Zq" + randomSecret(random) + "Zq";
            String text = "plain words " + random.nextInt() + " only";
            if (!text.contains(secret)) assertThat(new Redactor(List.of(secret)).scrub(text)).isEqualTo(text);
        });
    }

    private static String randomSecret(Random random) {
        String alphabet = "abcXYZ0123456789-_.~/+=&?%# !@:*()[]{}\\\"'";
        int length = 4 + random.nextInt(40);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String randomText(Random random) {
        StringBuilder sb = new StringBuilder();
        for (int i = random.nextInt(7); i > 0; i--) sb.append((char) ('a' + random.nextInt(26)));
        return sb.toString();
    }

    private static byte[] randomBytes(Random random, int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] flip(byte[] b) {
        byte[] out = b.clone();
        for (int i = 0; i < out.length; i++) out[i] ^= 0x55;
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static String longestCommonSubstring(String a, String b) {
        int best = 0;
        int end = 0;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > best) {
                        best = dp[i][j];
                        end = i;
                    }
                }
            }
        }
        return a.substring(end - best, end);
    }

    @Test
    @Tag("V1.5")
    void nullsAndTooShortSecretsAreIgnoredWithoutHarm() {
        Redactor r = new Redactor(java.util.Arrays.asList(null, "", "ab", "abcd"));
        assertThat(r.scrub(null)).isNull();
        assertThat(r.scrub("an abcd here, and ab")).isEqualTo("an *** here, and ab");
        assertThat(new Redactor(java.util.Arrays.asList((String) null)).scrub("untouched")).isEqualTo("untouched");
    }
}
