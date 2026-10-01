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
