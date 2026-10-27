package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.whatif.WhatIf;
import java.util.ArrayList;
import java.util.List;

/** What a change makes a run able to do that it could not before, and so must be looked at. */
final class UnsafeFlip {

    private UnsafeFlip() {}

    /** A change from fewer effects or a person to a new effect or no person. */
    static List<String> reasons(WhatIf.Outcome o) {
        List<String> out = new ArrayList<>();
        if (o.effects() != null)
            for (WhatIf.Effect e : o.effects().onlyCandidate())
                out.add("NEW EFFECT: " + e.statement() + " reaches " + e.via());
        return out;
    }
}
