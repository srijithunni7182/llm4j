package io.github.llm4j.getviral.app.account;

import java.util.List;
import java.util.Map;

/** The creator's connected social accounts, as shown to the UI (never tokens). */
public interface ConnectionsView {
    List<Map<String, Object>> forUser(String userId);
}
