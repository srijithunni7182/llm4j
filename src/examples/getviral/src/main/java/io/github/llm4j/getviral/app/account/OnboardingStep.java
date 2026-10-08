package io.github.llm4j.getviral.app.account;

/** Where a creator is in onboarding. Order matters: each step unlocks the next. */
public enum OnboardingStep {
    PROFILE, CONNECT, VOICE, TOUR, DONE;

    public OnboardingStep next() {
        return this == DONE ? DONE : values()[ordinal() + 1];
    }
}
