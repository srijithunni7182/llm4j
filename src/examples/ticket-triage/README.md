# Ticket triage with a classifier

A support message goes through an `intentclassifier` (a choice, a yes/no and a tone rating in one call on Jev), and only the cases a machine may
answer reach an agent. A doubtful classification (`unsure`) and a classifier outage both go to a person; an urgent customer is routed to a person
without a reply being written.

```
weave check triage.loom
weave audit triage.loom      # LA19 (info): the message text goes to the Jev service; pii: mask hides personal data first
JEV_API_KEY=... ANTHROPIC_API_KEY=... weave run triage.loom -i message="my parcel 8812 never arrived"
```

To use a chat model instead of Jev, change one line: `model: "claude-haiku-5-5"` (and `samples: 3` so its confidence means something).
The classifier is read, never obeyed: the message is handed over marked as untrusted data. Do not let the label alone approve a payment.
See the Loom guide section "Classifying" and the [study](../../../docs/specs/07-classifier/STUDY_REPORT.md).
