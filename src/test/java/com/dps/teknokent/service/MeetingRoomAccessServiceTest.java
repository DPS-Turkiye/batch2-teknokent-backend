package com.dps.teknokent.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingRoomAccessServiceTest {

	@Mock
	private MeetingRepository meetingRepository;

	@Mock
	private AppUserRepository appUserRepository;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private final Instant signedInAt = Instant.parse("2026-11-08T17:00:00Z");

	private MeetingRoomAccessService service;

	@BeforeEach
	void setUp() {
		service = new MeetingRoomAccessService(meetingRepository, appUserRepository);
	}

	@Test
	void allowsSubscriptionsOnlyForMeetingsOfTheCallerOrganization() {
		when(meetingRepository.existsByIdAndOrganizationId(meetingId, organizationId)).thenReturn(true);

		assertThat(service.isMeetingVisibleTo(meetingId, organizationId)).isTrue();
	}

	@Test
	void refusesSubscriptionsForUnknownOrForeignMeetings() {
		UUID otherOrganizationId = UUID.randomUUID();
		when(meetingRepository.existsByIdAndOrganizationId(meetingId, otherOrganizationId)).thenReturn(false);

		assertThat(service.isMeetingVisibleTo(meetingId, otherOrganizationId)).isFalse();
	}

	@Test
	void keepsAnApprovedMemberOfTheTeam() {
		member(UserAccountStatus.APPROVED, organizationId, null);

		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isTrue();
	}

	@Test
	void dropsAMemberWhoLeftOrMovedToAnotherStartup() {
		member(UserAccountStatus.APPROVED, null, null);
		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();

		member(UserAccountStatus.APPROVED, UUID.randomUUID(), null);
		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();
	}

	@Test
	void dropsAnAccountThatIsNoLongerApproved() {
		member(UserAccountStatus.REJECTED, organizationId, null);
		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();

		member(UserAccountStatus.PENDING, organizationId, null);
		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();
	}

	@Test
	void dropsASignInThatAnAdminEndedLater() {
		member(UserAccountStatus.APPROVED, organizationId, signedInAt.plusSeconds(30));

		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();
	}

	@Test
	void keepsASignInThatBeganAfterTheSignInsWereEnded() {
		member(UserAccountStatus.APPROVED, organizationId, signedInAt.minusSeconds(30));

		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isTrue();
	}

	@Test
	void dropsAnAccountThatNoLongerExists() {
		when(appUserRepository.findById(userId)).thenReturn(Optional.empty());

		assertThat(service.isStillMemberOf(userId, organizationId, signedInAt)).isFalse();
	}

	private void member(UserAccountStatus status, UUID memberOrganizationId, Instant signInsRevokedAt) {
		AppUser user = new AppUser();
		user.setId(userId);
		user.setStatus(status);
		if (memberOrganizationId != null) {
			Organization organization = new Organization();
			organization.setId(memberOrganizationId);
			user.setOrganization(organization);
		}
		user.setSignInsRevokedAt(signInsRevokedAt);
		when(appUserRepository.findById(userId)).thenReturn(Optional.of(user));
	}
}
