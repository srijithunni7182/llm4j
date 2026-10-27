package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Lens;
import java.util.ArrayList;
import java.util.List;

/**
 * What each security audit rule ({@code LA01} to {@code LA20}) looks for, in the words of the audit
 * chapter of the guide. The audit's rules stay in {@link
 * io.github.llm4j.loom.security.SecurityAudit}; this only lets a review explain them.
 */
public final class AuditCatalog {

    private AuditCatalog() {}

    private static final String[][] RULES = {
        {
            "LA01",
            "One agent has the lethal trifecta",
            "An agent that reads untrusted content, reaches private data and can send or act can be talked into leaking or acting. Remove one of the three legs, or put a person's approval in front of what it sends."
        },
        {
            "LA02",
            "Effects with no approval",
            "A tool that sends, writes or runs something is called with no person's yes. Name the tool in the agent's approve: list."
        },
        {
            "LA03",
            "No run budget",
            "Nothing caps what one run may spend. Add a run budget at the top of the script."
        },
        {
            "LA04",
            "Private data with no PII guard",
            "An agent can reach private data and has no guard { pii: ... }. Mask or block personal data before it reaches a model or a log."
        },
        {
            "LA05",
            "allow_private or allow_http",
            "A tool is allowed to reach private network addresses or use plain HTTP. Keep these off unless the tool needs them."
        },
        {
            "LA06",
            "A shell tool that can run interpreters",
            "A shell tool whose allow-list includes interpreters can run anything. Narrow the allow-list to the commands needed."
        },
        {
            "LA07",
            "Email recipients by pattern, or mail without TLS",
            "Recipients chosen by a pattern, or mail sent without TLS, widens who can be written to or read in transit."
        },
        {
            "LA08",
            "Content fetched from the network",
            "An MCP server, spec, skill or import is fetched from the network, so what runs depends on something outside the repository."
        },
        {
            "LA09",
            "Long-term memory on an agent that reads untrusted content",
            "What an untrusted page writes into memory is read back later as if it were a fact."
        },
        {
            "LA10",
            "A decision that may reach act automatically",
            "Autonomy to act is granted without a person. Require approval to move up, or keep the ceiling below act."
        },
        {
            "LA11",
            "A rewind that repeats side effects",
            "Going back crosses effects that already happened. Choose what to do about them."
        },
        {
            "LA12",
            "A file tool that may overwrite",
            "A file tool can overwrite existing files. Use a mode that cannot, or a folder that is only for output."
        },
        {
            "LA13",
            "Indexed documents become instructions-in-waiting",
            "Whatever is in an indexed document is shown to the model later, so it must be trusted."
        },
        {
            "LA14",
            "A tool the audit cannot see into",
            "Opaque Java or host tools are assumed to read untrusted content, reach private data and act. Declare what they reach with Name.reach."
        },
        {
            "LA15",
            "A tool whose reach is its author's declaration",
            "The audit believes the declared reach. Read the tool's code once to confirm it."
        },
        {
            "LA16",
            "A person will run output written from untrusted text",
            "Output written from untrusted text is meant to be run by a person. Show it for review, never run it automatically."
        },
        {
            "LA17",
            "A tool that acts as the person running the workflow",
            "The tool uses the invoker's credentials. Put approve: on it so nothing is done in their name unseen."
        },
        {
            "LA18",
            "A steerable agent is an input channel",
            "A person's text reaches this agent while it works. It is checked by its guard and recorded, but it is an input channel like any other."
        },
        {
            "LA19",
            "A classifier sends its state to a hosted service",
            "An intentclassify step sends the state of the run to a hosted service. Check which fields and whether pii: masks them."
        },
        {
            "LA20",
            "A grounded agent reads the open web",
            "An agent with grounding: web counts as reading untrusted content for the trifecta, whatever allow: says."
        },
    };

    /** One entry per audit rule. The severity varies with the situation, so none is given. */
    public static List<RuleInfo> rules() {
        List<RuleInfo> out = new ArrayList<>();
        for (String[] r : RULES)
            out.add(new RuleInfo(r[0], Lens.SECURITY, null, r[1], r[2], "audit"));
        return out;
    }
}
