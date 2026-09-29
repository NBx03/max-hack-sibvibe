package ru.sibvibe.approval.organization.service;

import org.springframework.stereotype.Component;
import ru.sibvibe.approval.common.dto.RoleRef;
import ru.sibvibe.approval.common.dto.UserRef;
import ru.sibvibe.approval.organization.dto.MemberView;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.MemberRole;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.MemberRoleRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Собирает {@link MemberView} пачкой запросов, а не по запросу на каждого участника. */
@Component
class MemberViewAssembler {

    private final AppUserRepository userRepository;
    private final MemberRoleRepository memberRoleRepository;
    private final RoleRepository roleRepository;

    MemberViewAssembler(
            AppUserRepository userRepository,
            MemberRoleRepository memberRoleRepository,
            RoleRepository roleRepository
    ) {
        this.userRepository = userRepository;
        this.memberRoleRepository = memberRoleRepository;
        this.roleRepository = roleRepository;
    }

    MemberView assemble(OrganizationMember member) {
        return assemble(List.of(member)).getFirst();
    }

    List<MemberView> assemble(Collection<OrganizationMember> members) {
        if (members.isEmpty()) {
            return List.of();
        }
        Map<Long, AppUser> users = userRepository.findAllById(
                        members.stream().map(OrganizationMember::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
        List<MemberRole> links = memberRoleRepository.findByMemberIdIn(
                members.stream().map(OrganizationMember::getId).toList());
        Set<Long> roleIds = new HashSet<>();
        links.forEach(link -> roleIds.add(link.getRoleId()));
        Map<Long, Role> roles = roleRepository.findAllById(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<Long, List<RoleRef>> rolesByMember = new HashMap<>();
        for (MemberRole link : links) {
            Role role = roles.get(link.getRoleId());
            rolesByMember.computeIfAbsent(link.getMemberId(), id -> new java.util.ArrayList<>())
                    .add(new RoleRef(role.getId(), role.getCode(), role.getName()));
        }
        return members.stream().map(member -> {
            AppUser user = users.get(member.getUserId());
            List<RoleRef> memberRoles = rolesByMember.getOrDefault(member.getId(), List.of()).stream()
                    .sorted(Comparator.comparingLong(RoleRef::id)).toList();
            return new MemberView(member.getId(), new UserRef(user.getId(), user.getFullName()),
                    member.getStatus().name(), memberRoles, member.isAdmin(), member.getJoinedAt());
        }).toList();
    }
}
