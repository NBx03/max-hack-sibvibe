package ru.sibvibe.approval.rules.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.rules.entity.OrganizationRuleSetting;

import java.util.List;
import java.util.Optional;

public interface OrganizationRuleSettingRepository
        extends JpaRepository<OrganizationRuleSetting, OrganizationRuleSetting.Key> {

    List<OrganizationRuleSetting> findByOrgId(Long orgId);

    Optional<OrganizationRuleSetting> findByOrgIdAndRuleId(Long orgId, Long ruleId);
}
