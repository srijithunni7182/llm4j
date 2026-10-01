package io.github.llm4j.loom.tools.generic;

import java.nio.file.Path;
import java.util.List;

/** A message ready to send: every address already parsed and checked. */
public record EmailMessage(MailAddress from, List<MailAddress> to, List<MailAddress> cc, List<MailAddress> bcc,
                           String subject, String body, boolean html, List<Path> attachments) {

    /** Everyone it goes to, in the order to, cc, bcc. */
    public List<MailAddress> recipients() {
        List<MailAddress> all = new java.util.ArrayList<>(to);
        all.addAll(cc);
        all.addAll(bcc);
        return all;
    }
}
