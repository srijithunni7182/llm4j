package io.github.llm4j.loom.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a store reaches its people: {@code <store>/channel.json} (optional), overridden by {@code --ask-via}. The token is never in the file, only the
 * name of the environment variable that holds it.
 */
public record ChannelConfig(String channel, String tokenEnv, Map<String, List<Long>> chats, Duration remindEvery, int remindAtMost, Duration expire,
                            List<String> command, String apiBase) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SPAN = Pattern.compile("(\\d+)\\s*(s|m|h|d)");

    public static final String DEFAULT_TOKEN_ENV = "TELEGRAM_BOT_TOKEN";
    public static final String DEFAULT_API = "https://api.telegram.org";

    public ChannelConfig {
        chats = chats == null ? Map.of() : Map.copyOf(chats);
        command = command == null ? List.of() : List.copyOf(command);
    }

    /** The configuration for a store, or empty when it uses the console. {@code askVia} (from the command line) wins over the file. */
    public static Optional<ChannelConfig> load(Path store, String askVia, Function<String, String> env) {
        Path file = store.toAbsolutePath().normalize().resolve("channel.json");
        Map<String, Object> m = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                m.putAll(JSON.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { }));
            } catch (IOException e) {
                throw new IllegalArgumentException(file + " is not valid JSON: " + String.valueOf(e.getMessage()).lines().findFirst().orElse("unreadable"));
            }
        }
        String channel = askVia != null ? askVia : (m.get("channel") == null ? "console" : String.valueOf(m.get("channel")));
        if (channel.equals("console")) return Optional.empty();
        if (!channel.equals("telegram") && !channel.equals("command")) {
            throw new IllegalArgumentException("unknown channel \"" + channel + "\" (use telegram, command or console)");
        }
        String tokenEnv = m.get("tokenEnv") == null ? DEFAULT_TOKEN_ENV : String.valueOf(m.get("tokenEnv"));
        Map<String, List<Long>> chats = new LinkedHashMap<>();
        Object c = m.get("chats");
        if (c instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) chats.put(String.valueOf(e.getKey()), ids(e.getValue()));
        }
        String fromEnv = env.apply("TELEGRAM_CHAT_IDS");
        if (!chats.containsKey("default") && fromEnv != null && !fromEnv.isBlank()) {
            List<Long> ids = new ArrayList<>();
            for (String part : fromEnv.split(",")) {
                if (!part.isBlank()) ids.add(parseId(part.strip()));
            }
            chats.put("default", ids);
        }
        Duration every = null;
        int atMost = 0;
        if (m.get("remind") instanceof Map<?, ?> r) {
            every = span(String.valueOf(r.get("every")));
            atMost = r.get("atMost") == null ? 1 : ((Number) r.get("atMost")).intValue();
        }
        Duration expire = m.get("expire") == null ? null : span(String.valueOf(m.get("expire")));
        List<String> command = new ArrayList<>();
        if (m.get("command") instanceof List<?> l) for (Object o : l) command.add(String.valueOf(o));
        String api = env.apply("TELEGRAM_API_BASE");
        return Optional.of(new ChannelConfig(channel, tokenEnv, chats, every, atMost, expire, command, api == null || api.isBlank() ? DEFAULT_API : api));
    }

    /** What is missing, in plain words, or null when the channel can be used. A missing allowlist never means "everyone". */
    public String problem(Function<String, String> env) {
        if (channel.equals("command")) return command.isEmpty() ? "the command channel needs \"command\": [program, args...] in channel.json" : null;
        String token = env.apply(tokenEnv);
        if (token == null || token.isBlank()) return "the Telegram bot token is missing: set the environment variable " + tokenEnv;
        if (allowedIds().isEmpty()) return "nobody is allowed to answer: set TELEGRAM_CHAT_IDS (or \"chats\" in channel.json) to the numbers of the chats that may";
        return null;
    }

    /** Every chat and user id that may answer. */
    public Set<Long> allowedIds() {
        Set<Long> out = new LinkedHashSet<>();
        chats.values().forEach(out::addAll);
        return out;
    }

    /** The chats a question for {@code to} (the name the script asked) goes to: its own, else the default. */
    public List<Long> chatsFor(String to) {
        List<Long> own = to == null ? null : chats.get(to);
        if (own != null && !own.isEmpty()) return own;
        List<Long> def = chats.get("default");
        if (def != null && !def.isEmpty()) return def;
        return chats.values().stream().findFirst().orElse(List.of());
    }

    static Duration span(String text) {
        Matcher m = SPAN.matcher(text == null ? "" : text.strip());
        if (!m.matches()) throw new IllegalArgumentException("\"" + text + "\" is not a time span (try 30m, 6h or 3d)");
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    private static List<Long> ids(Object value) {
        List<Long> out = new ArrayList<>();
        if (value instanceof List<?> l) for (Object o : l) out.add(parseId(String.valueOf(o)));
        else if (value != null) out.add(parseId(String.valueOf(value)));
        return out;
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + s + "\" is not a chat id (a number)");
        }
    }

}
