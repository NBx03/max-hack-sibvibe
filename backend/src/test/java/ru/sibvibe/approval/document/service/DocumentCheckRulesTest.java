package ru.sibvibe.approval.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Правило «нельзя отправить» общее у карточки и отправки. */
class DocumentCheckRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void genericTypeIsAlwaysSubmittable() {
        assertThat(DocumentCheckRules.blockReason(true, null, null)).isEmpty();
    }

    @Test
    void typedVersionWithoutFinishedCheckIsBlocked() {
        assertThat(DocumentCheckRules.blockReason(false, "FAILED", mapper.createArrayNode()))
                .contains(DocumentCheckRules.Reason.CHECK_NOT_DONE);
        assertThat(DocumentCheckRules.blockReason(false, null, null))
                .contains(DocumentCheckRules.Reason.CHECK_NOT_DONE);
    }

    @Test
    void blockerIssueBlocksButWarningAndInfoDoNot() throws Exception {
        assertThat(DocumentCheckRules.blockReason(false, "CHECKED",
                mapper.readTree("[{\"severity\":\"WARNING\"},{\"severity\":\"BLOCKER\"}]")))
                .contains(DocumentCheckRules.Reason.BLOCKER_ISSUES);
        assertThat(DocumentCheckRules.blockReason(false, "CHECKED",
                mapper.readTree("[{\"severity\":\"WARNING\"},{\"severity\":\"INFO\"}]"))).isEmpty();
        assertThat(DocumentCheckRules.blockReason(false, "CHECKED", mapper.createArrayNode())).isEmpty();
    }
}
