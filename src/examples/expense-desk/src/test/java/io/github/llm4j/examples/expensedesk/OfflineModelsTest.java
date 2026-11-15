package io.github.llm4j.examples.expensedesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfflineModelsTest {

    @Test
    void readsTheMerchantAmountCurrencyAndCategory() {
        assertThat(OfflineModels.readClaim("Lunch at Cafe Rio for the team, 1,250.75 rupees"))
                .containsEntry("merchant", "Cafe Rio")
                .containsEntry("amount", 1250.75)
                .containsEntry("currency", "INR")
                .containsEntry("category", "meals");
        assertThat(OfflineModels.readClaim("Keyboard from Keys & Co, €89")).containsEntry("currency", "EUR").containsEntry("category", "equipment");
    }

    @Test
    void leavesOutWhatTheTextDoesNotState() {
        assertThat(OfflineModels.readClaim("please pay me back soon")).doesNotContainKeys("merchant", "amount", "currency");
    }

    @Test
    void writesTheNoteFromTheOutcomeItIsTold() {
        assertThat(OfflineModels.note("<facts>\noutcome: paid\nemployee: Asha\nreference: PAY-0007\n</facts>")).contains("PAY-0007");
        assertThat(OfflineModels.note("<facts>\noutcome: refused by policy\nemployee: Sam\nreason: over the limit\n</facts>")).contains("over the limit");
        assertThat(OfflineModels.note("<facts>\noutcome: declined by the approver\nemployee: Ravi\n</facts>")).contains("not approved");
    }
}
