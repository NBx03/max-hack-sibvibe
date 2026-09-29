package ru.sibvibe.approval.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sibvibe.approval.organization.entity.InviteRole;

import java.util.Collection;
import java.util.List;

public interface InviteRoleRepository extends JpaRepository<InviteRole, InviteRole.Key> {
    List<InviteRole> findByInviteId(Long inviteId);

    List<InviteRole> findByInviteIdIn(Collection<Long> inviteIds);
}
