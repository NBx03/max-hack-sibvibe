package ru.sibvibe.approval.organization.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import ru.sibvibe.approval.common.api.DomainException;
import ru.sibvibe.approval.common.security.CurrentUser;
import ru.sibvibe.approval.organization.entity.AppUser;
import ru.sibvibe.approval.organization.entity.Invite;
import ru.sibvibe.approval.organization.entity.Organization;
import ru.sibvibe.approval.organization.entity.Role;
import ru.sibvibe.approval.organization.repository.AppUserRepository;
import ru.sibvibe.approval.organization.repository.InviteRepository;
import ru.sibvibe.approval.organization.repository.InviteRoleRepository;
import ru.sibvibe.approval.organization.repository.OrganizationRepository;
import ru.sibvibe.approval.organization.repository.RoleRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Приглашения: код с лимитом попыток, личная ссылка — предъявительский токен без прав администратора. */
class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
    private static final long ORG = 1;
    private static final long INVITE_ID = 40;
    private static final long USER = 77;

    private final InviteRepository inviteRepository = mock(InviteRepository.class);
    private final InviteRoleRepository inviteRoleRepository = mock(InviteRoleRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final MemberService memberService = mock(MemberService.class);
    private final CodeAttemptLimiter limiter = mock(CodeAttemptLimiter.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final InviteService service = new InviteService(inviteRepository, inviteRoleRepository, roleRepository,
            organizationRepository, userRepository, memberService, new InviteSecrets(8, 24),
            new InviteLinks(), limiter, events, Clock.fixed(NOW, ZoneOffset.UTC), 72);

    @Test
    void unknownCodeCountsAsFailedAttemptAndLooksNotFound() {
        assertThatThrownBy(() -> service.resolveOrgCode(USER, "ZZZZ2222"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVITE_NOT_FOUND");
                    assertThat(e.httpStatus().value()).isEqualTo(404);
                });
        verify(limiter).assertAllowed(USER);
        verify(limiter).recordFailure(USER);
    }

    @Test
    void malformedCodeIsTreatedLikeUnknownWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.resolveOrgCode(USER, "abc"))
                .isInstanceOf(DomainException.class);

        verify(limiter).recordFailure(USER);
        verify(inviteRepository, never()).findByCodeAndKindAndRevokedAtIsNull(any(), any());
    }

    @Test
    void validCodeDoesNotSpendAnAttempt() {
        Invite invite = invite(Invite.Kind.ORG_CODE);
        invite.setCode("ABCD2345");
        when(inviteRepository.findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE))
                .thenReturn(Optional.of(invite));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(false)));

        InviteService.ResolvedCode resolved = service.resolveOrgCode(USER, "abcd2345");

        assertThat(resolved.org().getName()).isEqualTo("Ромашка");
        verify(limiter, never()).recordFailure(anyLong());
    }

    @Test
    void codeOfDemoCompanyIsNeverAccepted() {
        Invite invite = invite(Invite.Kind.ORG_CODE);
        when(inviteRepository.findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE))
                .thenReturn(Optional.of(invite));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(true)));

        assertThatThrownBy(() -> service.resolveOrgCode(USER, "ABCD2345"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("INVITE_NOT_FOUND"));
    }

    @Test
    void personalLinkExpiresInSeventyTwoHoursAndKeepsChosenRolesOnly() {
        when(roleRepository.findByOrgIdAndIdIn(eq(ORG), any())).thenReturn(List.of(role(5)));
        when(inviteRepository.save(any(Invite.class))).thenAnswer(call -> {
            Invite saved = call.getArgument(0);
            saved.setId(INVITE_ID);
            return saved;
        });

        var view = service.createPersonal(admin(), List.of(5L));

        assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(72 * 3600));
        assertThat(view.link()).startsWith("https://max.ru/t354_hakaton_max_bot?startapp=p_");
        assertThat(view.roles()).hasSize(1);
    }

    @Test
    void personalLinkRequiresRolesOfOwnCompany() {
        when(roleRepository.findByOrgIdAndIdIn(eq(ORG), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.createPersonal(admin(), List.of(999L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.createPersonal(admin(), List.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        verify(inviteRepository, never()).save(any());
    }

    @Test
    void onlyAdministratorCreatesPersonalLinks() {
        var identity = new CurrentUser.UserIdentity(10, "Сотрудник");
        var member = new CurrentUser(identity, identity, 900L, ORG, Set.of(), false, null);

        assertThatThrownBy(() -> service.createPersonal(member, List.of(5L)))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_ADMIN"));
    }

    @Test
    void lostRaceReportsWhyTheLinkCannotBeUsed() {
        givenPersonalLink();
        when(inviteRepository.markPersonalUsed(eq(INVITE_ID), eq(USER), any(), eq(Invite.Kind.PERSONAL_LINK)))
                .thenReturn(0);

        Invite used = invite(Invite.Kind.PERSONAL_LINK);
        used.setUsedAt(NOW.minusSeconds(1));
        when(inviteRepository.findById(INVITE_ID)).thenReturn(Optional.of(used));
        assertReason("INVITE_USED");

        Invite revoked = invite(Invite.Kind.PERSONAL_LINK);
        revoked.setRevokedAt(NOW.minusSeconds(1));
        when(inviteRepository.findById(INVITE_ID)).thenReturn(Optional.of(revoked));
        assertReason("INVITE_REVOKED");

        Invite expired = invite(Invite.Kind.PERSONAL_LINK);
        expired.setExpiresAt(NOW.minusSeconds(1));
        when(inviteRepository.findById(INVITE_ID)).thenReturn(Optional.of(expired));
        assertReason("INVITE_EXPIRED");

        verify(memberService, never()).addMember(any(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), anyLong());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void personalLinkNeverMakesTheNewMemberAdministrator() {
        givenPersonalLink();
        when(inviteRepository.markPersonalUsed(eq(INVITE_ID), eq(USER), any(), eq(Invite.Kind.PERSONAL_LINK)))
                .thenReturn(1);
        when(inviteRoleRepository.findByInviteId(INVITE_ID)).thenReturn(List.of());
        var joined = new ru.sibvibe.approval.organization.entity.OrganizationMember();
        joined.setId(500L);
        when(memberService.addMember(any(), eq(USER), any(), eq(false), anyLong())).thenReturn(joined);

        service.acceptPersonal(USER, "token");

        verify(memberService).addMember(any(), eq(USER), any(), eq(false), anyLong());
        verify(events).publishEvent(any(ru.sibvibe.approval.organization.event.MemberJoinedByLinkEvent.class));
    }

    @Test
    void unknownOrDemoLinkLooksNotFound() {
        when(inviteRepository.findByToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.previewPersonal("nope"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("INVITE_NOT_FOUND"));

        when(inviteRepository.findByToken("demo")).thenReturn(Optional.of(invite(Invite.Kind.PERSONAL_LINK)));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(true)));
        assertThatThrownBy(() -> service.previewPersonal("demo"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo("INVITE_NOT_FOUND"));
    }

    // ---------- Отзыв личной ссылки: условный UPDATE, а не чтение с последующей записью ----------

    @Test
    void revocationIsAConditionalUpdateAndDoesNotTouchTheLoadedEntity() {
        Invite link = invite(Invite.Kind.PERSONAL_LINK);
        when(inviteRepository.findByIdAndOrgIdAndKind(INVITE_ID, ORG, Invite.Kind.PERSONAL_LINK))
                .thenReturn(Optional.of(link));
        when(inviteRepository.revokePersonal(eq(INVITE_ID), eq(ORG), any(), eq(Invite.Kind.PERSONAL_LINK)))
                .thenReturn(1);

        service.revokePersonal(admin(), INVITE_ID);

        // старое состояние сущности не должно записываться обратно: иначе затрёт used_at при гонке с вступлением
        assertThat(link.getRevokedAt()).isNull();
        verify(inviteRepository).revokePersonal(eq(INVITE_ID), eq(ORG), any(), eq(Invite.Kind.PERSONAL_LINK));
    }

    @Test
    void revokingALinkThatWasJustUsedReportsInviteUsed() {
        when(inviteRepository.findByIdAndOrgIdAndKind(INVITE_ID, ORG, Invite.Kind.PERSONAL_LINK))
                .thenReturn(Optional.of(invite(Invite.Kind.PERSONAL_LINK)));
        when(inviteRepository.revokePersonal(eq(INVITE_ID), eq(ORG), any(), eq(Invite.Kind.PERSONAL_LINK)))
                .thenReturn(0);
        Invite used = invite(Invite.Kind.PERSONAL_LINK);
        used.setUsedAt(NOW.minusSeconds(1));
        used.setUsedBy(USER);
        when(inviteRepository.findById(INVITE_ID)).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.revokePersonal(admin(), INVITE_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVITE_USED");
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void revokingAnAlreadyRevokedLinkChangesNothing() {
        when(inviteRepository.findByIdAndOrgIdAndKind(INVITE_ID, ORG, Invite.Kind.PERSONAL_LINK))
                .thenReturn(Optional.of(invite(Invite.Kind.PERSONAL_LINK)));
        when(inviteRepository.revokePersonal(eq(INVITE_ID), eq(ORG), any(), eq(Invite.Kind.PERSONAL_LINK)))
                .thenReturn(0);
        Invite revoked = invite(Invite.Kind.PERSONAL_LINK);
        revoked.setRevokedAt(NOW.minusSeconds(60));
        when(inviteRepository.findById(INVITE_ID)).thenReturn(Optional.of(revoked));

        service.revokePersonal(admin(), INVITE_ID);

        assertThat(revoked.getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void foreignLinkCannotBeRevoked() {
        when(inviteRepository.findByIdAndOrgIdAndKind(INVITE_ID, ORG, Invite.Kind.PERSONAL_LINK))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokePersonal(admin(), INVITE_ID))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        verify(inviteRepository, never()).revokePersonal(anyLong(), anyLong(), any(), any());
    }

    // ---------- Подача заявки по коду: проверка кода под блокировкой компании ----------

    @Test
    void joinCodeIsRecheckedUnderTheCompanyLock() {
        Invite code = invite(Invite.Kind.ORG_CODE);
        code.setCode("ABCD2345");
        when(inviteRepository.findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE))
                .thenReturn(Optional.of(code));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(false)));
        when(organizationRepository.findByIdForShare(ORG)).thenReturn(Optional.of(org(false)));

        InviteService.ResolvedCode resolved = service.resolveOrgCodeForJoin(USER, "ABCD2345");

        assertThat(resolved.invite()).isSameAs(code);
        var order = org.mockito.Mockito.inOrder(inviteRepository, organizationRepository);
        order.verify(inviteRepository).findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE);
        order.verify(organizationRepository).findByIdForShare(ORG);
        order.verify(inviteRepository).findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE);
    }

    @Test
    void codeRevokedWhileWaitingForTheLockIsRejectedWithoutPenaltyForTheUser() {
        Invite code = invite(Invite.Kind.ORG_CODE);
        code.setCode("ABCD2345");
        when(inviteRepository.findByCodeAndKindAndRevokedAtIsNull("ABCD2345", Invite.Kind.ORG_CODE))
                .thenReturn(Optional.of(code))            // до блокировки код действует
                .thenReturn(Optional.empty());            // пока ждали блокировку, его перевыпустили
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(false)));
        when(organizationRepository.findByIdForShare(ORG)).thenReturn(Optional.of(org(false)));

        assertThatThrownBy(() -> service.resolveOrgCodeForJoin(USER, "ABCD2345"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVITE_NOT_FOUND");
                    assertThat(e.httpStatus().value()).isEqualTo(404);
                });
        verify(limiter, never()).recordFailure(anyLong());
    }

    private void givenPersonalLink() {
        when(userRepository.findByIdForUpdate(USER)).thenReturn(Optional.of(new AppUser()));
        when(inviteRepository.findByToken("token")).thenReturn(Optional.of(invite(Invite.Kind.PERSONAL_LINK)));
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org(false)));
    }

    private void assertReason(String code) {
        assertThatThrownBy(() -> service.acceptPersonal(USER, "token"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(code);
                    assertThat(e.httpStatus().value()).isEqualTo(409);
                });
    }

    private Invite invite(Invite.Kind kind) {
        Invite invite = new Invite();
        invite.setId(INVITE_ID);
        invite.setOrgId(ORG);
        invite.setKind(kind);
        invite.setCreatedBy(10L);
        invite.setCreatedAt(NOW.minusSeconds(3600));
        if (kind == Invite.Kind.PERSONAL_LINK) {
            invite.setToken("token");
            invite.setExpiresAt(NOW.plusSeconds(3600));
        }
        return invite;
    }

    private Organization org(boolean demo) {
        Organization org = new Organization();
        org.setId(ORG);
        org.setName("Ромашка");
        org.setDemo(demo);
        return org;
    }

    private Role role(long id) {
        Role role = new Role();
        role.setId(id);
        role.setOrgId(ORG);
        role.setCode("R" + id);
        role.setName("Роль " + id);
        return role;
    }

    private CurrentUser admin() {
        var identity = new CurrentUser.UserIdentity(10, "Админ");
        return new CurrentUser(identity, identity, 900L, ORG, Set.of(), true, null);
    }
}
