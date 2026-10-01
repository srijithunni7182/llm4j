package io.github.llm4j.loom.generic.support;

import io.github.llm4j.loom.tools.generic.NetPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A DNS table: each host answers from a queue (the last answer repeats), and every lookup is counted. */
public final class StubResolver implements NetPolicy.Resolver {

    private final Map<String, Deque<List<InetAddress>>> answers = new HashMap<>();
    private final Map<String, Integer> lookups = new HashMap<>();

    /** Adds an answer for the host; several calls make a sequence (a changing answer, for rebinding tests). */
    public StubResolver answer(String host, String... addresses) {
        List<InetAddress> list = new ArrayList<>();
        for (String a : addresses) list.add(literal(a));
        answers.computeIfAbsent(host, h -> new ArrayDeque<>()).add(list);
        return this;
    }

    /** An IPv4-mapped IPv6 answer ({@code ::ffff:a.b.c.d}) that stays an {@link java.net.Inet6Address}, as a custom resolver could return. */
    public StubResolver mapped(String host, int a, int b, int c, int d) {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        try {
            answers.computeIfAbsent(host, h -> new ArrayDeque<>()).add(List.of(java.net.Inet6Address.getByAddress(host, bytes, 0)));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    @Override
    public synchronized List<InetAddress> resolve(String host) throws UnknownHostException {
        lookups.merge(host, 1, Integer::sum);
        Deque<List<InetAddress>> q = answers.get(host);
        if (q == null) throw new UnknownHostException(host);
        return q.size() > 1 ? q.poll() : q.peek();
    }

    public synchronized int lookups(String host) {
        return lookups.getOrDefault(host, 0);
    }

    /** An address from its literal text, without a DNS lookup. */
    public static InetAddress literal(String text) {
        try {
            return InetAddress.getByAddress(text, InetAddress.getByName(text).getAddress());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
