package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.BottleneckRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class AdminOverviewServiceTest {

	@Mock
	private DomainLookupService domainLookupService;
	@Mock
	private AppUserRepository appUserRepository;
	@Mock
	private MentorshipService mentorshipService;
	@Mock
	private MentorshipRequestService mentorshipRequestService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramStartupRepository programStartupRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private OrganizationRepository organizationRepository;
	@Mock
	private MeetingRepository meetingRepository;
	@Mock
	private BottleneckRepository bottleneckRepository;

	@InjectMocks
	private AdminOverviewService service;

	@Test
	void rejectsAnyoneWhoIsNotAnAdmin() {
		AppUser mentor = new AppUser();
		mentor.setRole(UserRole.MENTOR);
		UUID actorId = UUID.randomUUID();
		when(domainLookupService.requireUser(actorId)).thenReturn(mentor);

		assertThatThrownBy(() -> service.overview(actorId))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Only an admin can view the admin overview.");
	}
}
