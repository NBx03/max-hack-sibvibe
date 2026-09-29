package ru.sibvibe.approval.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.rules.RuleEngine.CheckType;
import ru.sibvibe.approval.rules.RuleEngine.RuleKind;
import ru.sibvibe.approval.rules.RuleEngine.Severity;
import ru.sibvibe.approval.rules.entity.OrganizationRuleSetting;
import ru.sibvibe.approval.rules.entity.RequirementRule;
import ru.sibvibe.approval.rules.repository.OrganizationRuleSettingRepository;
import ru.sibvibe.approval.rules.repository.RequirementRuleRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Настройка правил компании поверх шаблона: что хранится, что запрещено, каким становится правило. */
class RuleCatalogServiceTest {

    private static final long ORG = 1;
    private static final long ADMIN = 9;

    private final RequirementRuleRepository rules = mock(RequirementRuleRepository.class);
    private final OrganizationRuleSettingRepository settings = mock(OrganizationRuleSettingRepository.class);
    private final RuleCatalogService service = new RuleCatalogService(rules, settings, new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void productRuleWithTheCompanysOwnSourceBecomesACompanyRule() {
        RequirementRule rule = rule(10L, CheckType.REQUIRED, RuleKind.PRODUCT_RULE, null);
        when(rules.findById(10L)).thenReturn(Optional.of(rule));
        when(settings.findByOrgIdAndRuleId(ORG, 10L)).thenReturn(Optional.empty());
        when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, Severity.WARNING, "Текст", "Регламент ООО «Ромашка»", "п. 1", null, null));

        assertThat(result.kind()).isEqualTo(RuleKind.INTERNAL_POLICY);
        assertThat(result.sourceTitle()).isEqualTo("Регламент ООО «Ромашка»");
        assertThat(result.sourceUrl()).as("ссылка шаблона к чужому документу не относится").isNull();
        assertThat(result.customized()).isTrue();
    }

    @Test
    void settingIdenticalToTheTemplateIsNotStored() {
        RequirementRule rule = rule(10L, CheckType.MIN_LENGTH, RuleKind.PRODUCT_RULE, "50");
        when(rules.findById(10L)).thenReturn(Optional.of(rule));
        when(settings.findByOrgIdAndRuleId(ORG, 10L)).thenReturn(Optional.empty());
        when(settings.existsById(any())).thenReturn(true);

        var result = service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, rule.getSeverity(), "  " + rule.getDescription() + " ", rule.getSourceTitle(), null, 50, null));

        assertThat(result.customized()).isFalse();
        verify(settings, never()).save(any(OrganizationRuleSetting.class));
        verify(settings).deleteById(new OrganizationRuleSetting.Key(ORG, 10L));
    }

    /** Ревью: свой источник — другое название ИЛИ другой пункт. */
    @Test
    void sameTitleWithAnotherPointIsTheCompanysOwnSource() {
        RequirementRule rule = rule(10L, CheckType.REQUIRED, RuleKind.INTERNAL_POLICY, null);
        rule.setSourceRef("п. 1");
        when(rules.findById(10L)).thenReturn(Optional.of(rule));
        when(settings.findByOrgIdAndRuleId(ORG, 10L)).thenReturn(Optional.empty());
        when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, rule.getSeverity(), rule.getDescription(), rule.getSourceTitle(), "п. 2", null, null));

        assertThat(result.customized()).isTrue();
        assertThat(result.sourceRef()).isEqualTo("п. 2");
    }

    /** Ревью: стёрли название своего документа, пункт в форме остался — это источник шаблона. */
    @Test
    void emptyTitleMeansTheTemplateSourceWhateverThePoint() {
        RequirementRule rule = rule(10L, CheckType.REQUIRED, RuleKind.INTERNAL_POLICY, null);
        rule.setSourceRef("п. 1");
        when(rules.findById(10L)).thenReturn(Optional.of(rule));
        OrganizationRuleSetting own = new OrganizationRuleSetting();
        own.setOrgId(ORG);
        own.setRuleId(10L);
        own.setSourceTitle("Инструкция ООО «Ромашка»");
        own.setSourceRef("п. 4.2");
        when(settings.findByOrgIdAndRuleId(ORG, 10L)).thenReturn(Optional.of(own));
        when(settings.existsById(any())).thenReturn(true);

        var result = service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, rule.getSeverity(), rule.getDescription(), "  ", "п. 4.2", null, null));

        assertThat(result.customized()).isFalse();
        assertThat(result.sourceTitle()).isEqualTo(rule.getSourceTitle());
        assertThat(result.sourceRef()).isEqualTo("п. 1");
        verify(settings).deleteById(new OrganizationRuleSetting.Key(ORG, 10L));
    }

    @Test
    void expectedValueIsValidated() {
        RequirementRule length = rule(10L, CheckType.MIN_LENGTH, RuleKind.PRODUCT_RULE, "50");
        RequirementRule oneOf = rule(11L, CheckType.ONE_OF, RuleKind.INTERNAL_POLICY, "[\"а\",\"б\"]");
        when(rules.findById(10L)).thenReturn(Optional.of(length));
        when(rules.findById(11L)).thenReturn(Optional.of(oneOf));

        assertThatThrownBy(() -> service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, Severity.INFO, "Текст", null, null, 0, null))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.update(ORG, 11L, ADMIN, new RuleCatalogService.Change(
                true, Severity.INFO, "Текст", null, null, null, List.of(" ", "")))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.update(ORG, 10L, ADMIN, new RuleCatalogService.Change(
                true, Severity.INFO, "   ", null, null, null, null))).isInstanceOf(DomainException.class);
    }

    private static RequirementRule rule(Long id, CheckType check, RuleKind kind, String expected) {
        RequirementRule rule = new RequirementRule();
        rule.setId(id);
        rule.setDocumentTypeId(5L);
        rule.setFieldName("body");
        rule.setDescription("Шаблонный текст");
        rule.setCheckType(check);
        rule.setExpected(expected);
        rule.setKind(kind);
        rule.setSeverity(Severity.INFO);
        rule.setSourceTitle("Правило продукта");
        rule.setSourceUrl("https://example.org/template");
        rule.setSourceCheckedAt(Instant.parse("2026-09-21T00:00:00Z"));
        return rule;
    }
}
