package ru.sibvibe.approval.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.organization.entity.Role;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    List<Role> findByOrgIdOrderById(Long orgId);

    /** Роль ищется только среди ролей компании: чужой id даёт пустой результат. */
    Optional<Role> findByIdAndOrgId(Long id, Long orgId);

    List<Role> findByOrgIdAndIdIn(Long orgId, Collection<Long> ids);

    boolean existsByOrgIdAndNameIgnoreCase(Long orgId, String name);

    boolean existsByOrgIdAndNameIgnoreCaseAndIdNot(Long orgId, String name, Long id);
}
