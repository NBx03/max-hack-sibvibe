package ru.sibvibe.approval.document.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.document.DocumentLookup;
import ru.sibvibe.approval.document.entity.Document;
import ru.sibvibe.approval.document.repository.DocumentReadRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ACL и SQL проверяет {@code DocumentIntegrationTest} на настоящем Postgres; здесь - только форма. */
class DocumentLookupServiceTest {

    private final DocumentReadRepository repository = mock(DocumentReadRepository.class);
    private final DocumentLookupService service = new DocumentLookupService(repository);

    @Test
    void mapsRepositoryRowsToPortMatchesWithStatusAsAPlainString() {
        when(repository.findByTitleFragment(1L, 7L, "ноутбук", 5)).thenReturn(List.of(
                new DocumentReadRepository.TitleMatch(500L, "Закупка ноутбуков", Document.Status.IN_APPROVAL,
                        DisplayStatus.IN_ENDORSEMENT, 2)));

        List<DocumentLookup.Match> matches = service.findByTitleFragment(1L, 7L, "ноутбук", 5);

        // Бот показывает тот же статус, что карточка и списки: «на утверждении», а не «на согласовании».
        assertThat(matches).containsExactly(new DocumentLookup.Match(500L, "Закупка ноутбуков", "IN_ENDORSEMENT", 2));
    }

    @Test
    void blankFragmentSkipsTheRepositoryCall() {
        assertThat(service.findByTitleFragment(1L, 7L, "   ", 5)).isEmpty();
        verify(repository, never()).findByTitleFragment(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void nullFragmentSkipsTheRepositoryCall() {
        assertThat(service.findByTitleFragment(1L, 7L, null, 5)).isEmpty();
        verify(repository, never()).findByTitleFragment(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void trimsTheFragmentBeforeSearching() {
        when(repository.findByTitleFragment(1L, 7L, "ноутбук", 5)).thenReturn(List.of());

        service.findByTitleFragment(1L, 7L, "  ноутбук  ", 5);

        verify(repository).findByTitleFragment(1L, 7L, "ноутбук", 5);
    }
}
