package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@code plan.json}: reading, writing and fingerprinting a {@link Plan}. */
public final class PlanJson {

    private PlanJson() {}

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String write(Plan plan) {
        try {
            return MAPPER.writeValueAsString(plan) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads a plan; throws {@link IllegalArgumentException} with words for the person. */
    public static Plan read(String json) {
        try {
            Plan plan = MAPPER.readValue(json, Plan.class);
            if (plan == null) throw new IllegalArgumentException("The plan is empty.");
            return plan;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "The plan is not valid JSON for a plan: " + firstLine(e.getMessage()));
        }
    }

    /** A stable fingerprint of the plan: the same plan gives the same text, edits change it. */
    public static String hash(Plan plan) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(write(plan).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "unreadable";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
