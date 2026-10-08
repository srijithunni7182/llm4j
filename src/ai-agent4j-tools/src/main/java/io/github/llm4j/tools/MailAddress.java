package io.github.llm4j.tools;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One mail address, parsed strictly: {@code a@example.com} or {@code Name <a@example.com>}. Anything that
 * could add a header or a second recipient (line breaks, commas, angle brackets in odd places) is rejected,
 * so what is checked is exactly what is sent.
 */
public record MailAddress(String display, String address) {

    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final Pattern LOCAL = Pattern.compile(ATOM + "(\\.(" + ATOM + "))*");
    private static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern NAMED = Pattern.compile("([^<>\"\\\\,;@]{0,100})<([^<>\\s]+)>");
    private static final int MAX_LENGTH = 254;

    /** @throws IllegalArgumentException if the text isn't a single, plain address */
    public static MailAddress parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("the address is empty");
        String t = text.strip();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029) {
                throw new IllegalArgumentException("the address contains a control or line-break character");
            }
        }
        if (t.length() > MAX_LENGTH) throw new IllegalArgumentException("the address is too long");
        String display = null;
        String address = t;
        var named = NAMED.matcher(t);
        if (named.matches()) {
            display = named.group(1).strip();
            address = named.group(2);
            if (display.isEmpty()) display = null;
        }
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) throw new IllegalArgumentException("not an address: " + shorten(t));
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        if (!LOCAL.matcher(local).matches() || local.length() > 64 || !DOMAIN.matcher(domain).matches()) {
            throw new IllegalArgumentException("not an address: " + shorten(t));
        }
        return new MailAddress(display, local + "@" + domain.toLowerCase(Locale.ROOT));
    }

    /** True if the address equals the pattern ({@code a@x.com}) or the pattern is {@code *@x.com}. */
    public boolean matches(String pattern) {
        String p = pattern.toLowerCase(Locale.ROOT).strip();
        String a = address.toLowerCase(Locale.ROOT);
        if (p.startsWith("*@")) return a.endsWith(p.substring(1)) && a.length() > p.length() - 1;
        return a.equals(p);
    }

    /** True if {@code pattern} is a usable allow-list entry. */
    public static boolean validPattern(String pattern) {
        try {
            if (pattern.startsWith("*@")) return DOMAIN.matcher(pattern.substring(2)).matches();
            return parse(pattern).display() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String formatted() {
        return display == null ? address : display + " <" + address + ">";
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
