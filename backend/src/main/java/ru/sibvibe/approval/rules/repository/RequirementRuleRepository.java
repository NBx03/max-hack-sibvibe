package ru.sibvibe.approval.rules.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.rules.entity.RequirementRule;

import java.util.List;

public interface RequirementRuleRepository extends JpaRepository<RequirementRule, Long> {
    List<RequirementRule> findByDocumentTypeIdOrderById(Long documentTypeId);
}
