package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.OrganizationMember;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.OrganizationMemberRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Единственная точка входа {@code bot} в {@code organization} (ARCHITECTURE.md, раздел 2). */
class NotificationDirectoryServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final OrganizationMemberRepository members = mock(OrganizationMemberRepository.class);
    private final NotificationDirectoryService service = new NotificationDirectoryService(users, members,
            mock(ru.sibvibe.approval.organization.repository.JoinRequestRepository.class));

    @Test
    void findReturnsMaxUserIdAndDisplayName() {
        when(users.findById(7L)).thenReturn(Optional.of(user(7L, 910000007L, "Иван Иванов")));

        Optional<NotificationDirectoryService.Recipient> found = service.find(7L);

        assertThat(found).contains(new NotificationDirectoryService.Recipient(910000007L, "Иван Иванов"));
    }

    @Test
    void findOfUnknownUserIsEmpty() {
        when(users.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.find(7L)).isEmpty();
    }

    @Test
    void findBySetSkipsMissingUsersInsteadOfFailing() {
        when(users.findAllById(Set.of(1L, 2L))).thenReturn(List.of(user(1L, 910000001L, "Один")));

        Map<Long, NotificationDirectoryService.Recipient> found = service.find(Set.of(1L, 2L));

        assertThat(found).containsOnlyKeys(1L);
        assertThat(found.get(1L).maxUserId()).isEqualTo(910000001L);
    }

    @Test
    void findBySetOfEmptyIdsSkipsTheRepositoryCall() {
        assertThat(service.find(Set.<Long>of())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(users);
    }

    @Test
    void activeAdminsResolvesMemberUserIdsToRecipients() {
        when(members.findByOrgIdAndStatusAndAdminTrue(1L, OrganizationMember.Status.ACTIVE))
                .thenReturn(List.of(member(10L, 5L), member(11L, 6L)));
        when(users.findAllById(Set.of(5L, 6L)))
                .thenReturn(List.of(user(5L, 910000005L, "Админ Пять"), user(6L, 910000006L, "Админ Шесть")));

        List<NotificationDirectoryService.Recipient> admins = service.activeAdmins(1L);

        assertThat(admins).extracting(NotificationDirectoryService.Recipient::maxUserId)
                .containsExactlyInAnyOrder(910000005L, 910000006L);
    }

    @Test
    void findRealMembershipByMaxUserIdResolvesUserIdAndOrgId() {
        when(users.findByMaxUserId(910000007L)).thenReturn(Optional.of(user(7L, 910000007L, "Иван Иванов")));
        when(members.findFirstByUserIdAndStatusAndDemoFalse(7L, OrganizationMember.Status.ACTIVE))
                .thenReturn(Optional.of(member(10L, 7L)));

        Optional<NotificationDirectoryService.Membership> found = service.findRealMembershipByMaxUserId(910000007L);

        assertThat(found).contains(new NotificationDirectoryService.Membership(7L, 1L));
    }

    @Test
    void findRealMembershipByMaxUserIdIsEmptyForAnUnknownMaxUserId() {
        when(users.findByMaxUserId(910000007L)).thenReturn(Optional.empty());

        assertThat(service.findRealMembershipByMaxUserId(910000007L)).isEmpty();
    }

    @Test
    void findRealMembershipByMaxUserIdIsEmptyWithoutAnActiveRealCompany() {
        when(users.findByMaxUserId(910000007L)).thenReturn(Optional.of(user(7L, 910000007L, "Иван Иванов")));
        when(members.findFirstByUserIdAndStatusAndDemoFalse(7L, OrganizationMember.Status.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.findRealMembershipByMaxUserId(910000007L)).isEmpty();
    }

    private AppUser user(long id, long maxUserId, String name) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setMaxUserId(maxUserId);
        user.setFullName(name);
        user.setCreatedAt(Instant.EPOCH);
        return user;
    }

    private OrganizationMember member(long memberId, long userId) {
        OrganizationMember member = new OrganizationMember();
        member.setId(memberId);
        member.setUserId(userId);
        member.setOrgId(1L);
        member.setAdmin(true);
        member.setStatus(OrganizationMember.Status.ACTIVE);
        member.setJoinedAt(Instant.EPOCH);
        return member;
    }
}
