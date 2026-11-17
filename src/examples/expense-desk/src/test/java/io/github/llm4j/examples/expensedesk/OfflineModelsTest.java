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
        assertThat(OfflineModels.note("<facts> outcome: paid | employee: Asha | reference: PAY-0007 </facts>")).contains("PAY-0007");
        assertThat(OfflineModels.note("<facts> outcome: refused by policy | employee: Sam | reason: over the limit </facts>")).contains("over the limit");
        assertThat(OfflineModels.note("<facts> outcome: declined by the approver | employee: Ravi </facts>")).contains("not approved");
    }
}
