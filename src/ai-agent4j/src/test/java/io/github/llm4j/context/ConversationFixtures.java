package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The fixed conversations every compactor is tested on, and a seeded generator for many more. */
final class ConversationFixtures {
    private ConversationFixtures() {}

    static Map<String, List<Message>> fixed() {
        Map<String, List<Message>> m = new LinkedHashMap<>();
        m.put("empty", List.of());
        m.put("system and task only", new Convo().system("You are an agent.").task("Find the answer.").build());
        m.put("short, fits", new Convo().system("sys").task("task").step("search", 80).answer("done").build());
        Convo many = new Convo().system("sys").task("task");
        for (int i = 0; i < 40; i++) many.step("search", 160);
        m.put("many small turns", many.build());
        Convo huge = new Convo().system("sys").task("task");
        for (int i = 0; i < 6; i++) huge.step("fetch", 8_000);
        m.put("few huge results", huge.build());
        Convo par = new Convo().system("sys").task("task");
        for (int i = 0; i < 8; i++) par.parallel(4, 600, "lookup");
        m.put("parallel tool calls", par.build());
        m.put("ends with a tool result", new Convo().system("sys").task("task").step("a", 900).step("b", 900).step("c", 900).build());
        m.put("already compacted", new Convo().system("sys").task("task")
                .add(Message.builder().role(Message.Role.USER).content("[12 earlier messages were removed to save space.]")
                        .providerData(Map.of(Messages.COMPACTION, "drop_oldest", Messages.DROPPED, 12)).build())
                .step("a", 700).step("b", 700).step("c", 700).step("d", 700).step("e", 700).build());
        m.put("non-ASCII", new Convo().system("système").task("日本語のタスク — résumé 🚀").step("recherche", 500).step("検索", 500)
                .step("поиск", 500).step("a", 500).step("b", 500).step("c", 500).build());
        m.put("one very long line", new Convo().system("sys").task("t".repeat(30_000)).step("a", 30_000).step("b", 30_000)
                .step("c", 30_000).step("d", 30_000).step("e", 30_000).build());
        m.put("pinned in the middle", new Convo().system("sys").task("task").step("a", 600)
                .add(Message.builder().role(Message.Role.USER).content("Remember: the budget is 500.")
                        .providerData(Map.of(Messages.PINNED, true)).build())
                .step("b", 600).step("c", 600).step("d", 600).step("e", 600).step("f", 600).build());
        return m;
    }

    /** A random but reproducible conversation: the same seed and index give the same messages. */
    static List<Message> generated(long seed, int index) {
        Random r = new Random(seed * 1_000_003L + index);
        Convo c = new Convo();
        if (r.nextInt(10) > 0) c.system("system " + index);
        if (r.nextInt(10) > 0) c.task("task " + index + " " + Convo.filler("t", r.nextInt(2_000)));
        int exchanges = r.nextInt(30);
        for (int i = 0; i < exchanges; i++) {
            int kind = r.nextInt(10);
            if (kind == 0) c.answer(Convo.filler("a", r.nextInt(3_000)));
            else if (kind == 1) {
                c.add(Message.builder().role(Message.Role.USER).content("note " + i)
                        .providerData(Map.of(Messages.PINNED, true)).build());
            } else c.parallel(1 + (kind > 7 ? r.nextInt(5) : 0), r.nextInt(20_000), "tool" + r.nextInt(4));
        }
        return c.build();
    }
}
