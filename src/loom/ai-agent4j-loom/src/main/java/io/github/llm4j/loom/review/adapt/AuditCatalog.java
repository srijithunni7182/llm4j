package io.github.llm4j.loom.review.adapt;

import io.github.llm4j.loom.review.engine.RuleInfo;
import io.github.llm4j.loom.review.model.Lens;
import java.util.ArrayList;
import java.util.List;

/**
 * What each security audit rule ({@code LA01} to {@code LA21}) looks for, in the words of the audit
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
        {
            "LA21",
            "The transcript carries untrusted text to a later agent",
            "An agent that receives the whole transcript after an agent that reads untrusted content has run is exposed to what it read. Give it transcript: none or own, or split the work."
        },
        {
            "LA23",
            "An agent reads a file chosen at run time",
            "An agent sent a picture, recording or document whose file name is filled in at run time reads untrusted content: it can carry instructions that masking cannot see. Name the file in the script where you can."
        },
        {
            "LA24",
            "A workflow takes input from outside",
            "An expose block lets other agents call a workflow. Everything it reaches counts as reading untrusted content, so the lethal trifecta rule can fire. Keep those agents away from private data and from tools that send or act."
        },
        {
            "LA25",
            "Data leaves to a remote agent",
            "A delegate to a remote agent sends text and files to another organisation. Nothing in the script masks personal data first unless an agent of the workflow has a pii guard. Medium when the workflow reaches private data and nothing masks it."
        },
        {
            "LA26",
            "A channel answers with no person",
            "An agent_channel with audience automatic answers a party from outside without a person looking. High when a remote agent uses it, because that agent can ask for authorization."
        },
        {
            "LA27",
            "An exposed workflow has no spend cap",
            "An expose with neither a budget of its own nor a run budget leaves the cost to whoever can call it. Add a budget."
        },
        {
            "LA28",
            "A script can steer a started workflow",
            "A steer statement, or a supervise rule that steers, puts words written in the script in front of a steerable agent of a started workflow while it works: an input channel, as a steerable agent is for a person (LA18). Names the agents it can reach; medium when one of them also reaches private data and can send or act. Cancelling is not an input channel and gets no rule."
        },
        {
            "LA29",
            "A model judges a started workflow",
            "A judged assertion in a supervise block asks an evaluator, on a cadence and at most max times, whether a sentence holds of a started workflow. Lists what the judge sees (facts only, or content with seeing content: low, because content may carry instructions) and what a fail can cause: only steer, wrap_up or cancel use partial, with text fixed in the script. It is a model's opinion and is not under the earned-autonomy ladder."
        },
        {
            "LA30",
            "A workflow reads a signal from another run",
            "A wait signal in a child, a wait handle signal or a when signal rule in a parent lets text written by another run enter the workflow's variables. Names the signal and the side that can send it; it is read as unchecked. Medium when the text is written into a model's prompt or a task's argument, directly or through a copy such as set copy = text, info otherwise. A signal causes nothing by itself and can never become a steer's text. There is no rule for sending a signal: the parent already gets the child's result."
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
