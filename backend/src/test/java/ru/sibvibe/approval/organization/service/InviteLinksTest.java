package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Формат ссылок на мини-приложение - API_CONTRACTS.md, «Префиксы startParam». */
class InviteLinksTest {

    private final InviteLinks links = new InviteLinks();

    @Test
    void documentLinkUsesDPrefix() {
        assertThat(links.documentLink(500L)).isEqualTo("https://max.ru/t354_hakaton_max_bot?startapp=d_500");
    }

    @Test
    void appLinkHasNoStartParam() {
        assertThat(links.appLink()).isEqualTo("https://max.ru/t354_hakaton_max_bot");
    }

    @Test
    void orgCodeAndPersonalLinksStillUseTheirOwnPrefixes() {
        assertThat(links.orgCodeLink("ABC12345")).isEqualTo("https://max.ru/t354_hakaton_max_bot?startapp=c_ABC12345");
        assertThat(links.personalLink("token123")).isEqualTo("https://max.ru/t354_hakaton_max_bot?startapp=p_token123");
    }
}
