# Conversation-level metrics

Judge multi-turn behavior: does the assistant remember, stay in role, finish what the user asked, and stay on topic?

[← eval4j README](../README.md) · [All docs](README.md)

---

## Conversation-level metrics

```java
ConversationJudgePresets conv = ConversationJudgePresets.using(judgeClient);
Transcript transcript = Transcript.fromResults(userInputs, agentResults);  // or Transcript.builder()

assertThat(transcript)
    .is(conv.knowledgeRetention())
    .is(conv.roleAdherence("You are a polite banking assistant who never gives investment advice."))
    .is(conv.conversationCompleteness(List.of("cancel the card", "confirm the address")))
    .is(conv.conversationRelevancy());

// or straight from ConversationAssert
assertThat(results).conversation(userInputs).is(conv.knowledgeRetention());
```

Failure messages name the offending turns (`turn 4: asked for the user's name again`). Long transcripts
are windowed and the reason says so.
