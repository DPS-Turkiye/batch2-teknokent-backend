package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.dps.teknokent.domain.MentorLabelColor;
import com.dps.teknokent.domain.MentorLabelLimits;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.MentorLabelRequest;
import com.dps.teknokent.dto.responses.MentorLabelResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorLabel;
import com.dps.teknokent.entity.ProgramMentorLabelAssignment;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.ProgramMentorLabelAssignmentRepository;
import com.dps.teknokent.repository.ProgramMentorLabelRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.Texts;

@ExtendWith(MockitoExtension.class)
class MentorLabelServiceTest {

	private static final UUID COORDINATOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID MENTEE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
	private static final UUID MENTOR = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	private static final UUID OTHER_MENTOR = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
	private static final UUID PROGRAM_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID PROGRAM_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private MenteeProgramService menteeProgramService;
	@Mock
	private ProgramRepository programRepository;
	@Mock
	private ProgramMentorRepository programMentorRepository;
	@Mock
	private ProgramMentorLabelRepository labelRepository;
	@Mock
	private ProgramMentorLabelAssignmentRepository assignmentRepository;
	@Mock
	private AppUserRepository appUserRepository;

	@InjectMocks
	private MentorLabelService service;

	@Test
	void createsALabelWithItsNameTidiedUp() {
		editable(program(PROGRAM_A, true));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of());
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of());

		service.create(COORDINATOR, PROGRAM_A, new MentorLabelRequest(null, "  Yapay   zekâ ", MentorLabelColor.TEAL));

		ArgumentCaptor<ProgramMentorLabel> saved = ArgumentCaptor.forClass(ProgramMentorLabel.class);
		verify(labelRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("Yapay zekâ");
		assertThat(saved.getValue().getColor()).isEqualTo(MentorLabelColor.TEAL);
		assertThat(saved.getValue().getProgramId()).isEqualTo(PROGRAM_A);
		assertThat(saved.getValue().getCreatedBy()).isEqualTo(COORDINATOR);
	}

	@Test
	void refusesASecondLabelWithTheSameNameWhateverItsCase() {
		editable(program(PROGRAM_A, true));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A))
				.thenReturn(List.of(label(PROGRAM_A, "Fintech")));

		assertThatThrownBy(() -> service.create(COORDINATOR, PROGRAM_A,
				new MentorLabelRequest(null, "FINTECH", MentorLabelColor.BLUE)))
				.isInstanceOf(ConflictException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelNameTaken"));
		verify(labelRepository, never()).saveAndFlush(any());
	}

	@Test
	void refusesANewLabelOnceTheProgramHasFifty() {
		editable(program(PROGRAM_A, true));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(IntStream
				.range(0, MentorLabelLimits.PER_PROGRAM).mapToObj(index -> label(PROGRAM_A, "Etiket " + index))
				.toList());

		assertThatThrownBy(() -> service.create(COORDINATOR, PROGRAM_A,
				new MentorLabelRequest(null, "Yeni", MentorLabelColor.BLUE)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelProgramFull", MentorLabelLimits.PER_PROGRAM));
		verify(labelRepository, never()).saveAndFlush(any());
	}

	@Test
	void acceptsThirtyCharactersAndRefusesThirtyOne() {
		String thirty = "a".repeat(MentorLabelLimits.NAME_MAX_LENGTH);

		assertThat(MentorLabelService.normalizedName(" " + thirty + " ")).isEqualTo(thirty);
		assertThatThrownBy(() -> MentorLabelService.normalizedName(thirty + "b"))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelNameTooLong", MentorLabelLimits.NAME_MAX_LENGTH));
		assertThatThrownBy(() -> MentorLabelService.normalizedName("   "))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelNameRequired"));
	}

	@Test
	void changesNothingWhileTheProgramSwitchIsOff() {
		editable(program(PROGRAM_A, false));
		UUID labelId = UUID.randomUUID();
		String off = Texts.forRequest("error.mentorLabelsOff");

		assertThatThrownBy(() -> service.create(COORDINATOR, PROGRAM_A,
				new MentorLabelRequest(null, "Fintech", MentorLabelColor.BLUE))).hasMessage(off);
		assertThatThrownBy(() -> service.update(COORDINATOR, PROGRAM_A, labelId,
				new MentorLabelRequest(null, "Fintech", MentorLabelColor.BLUE))).hasMessage(off);
		assertThatThrownBy(() -> service.delete(COORDINATOR, PROGRAM_A, labelId)).hasMessage(off);
		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, List.of(labelId)))
				.hasMessage(off);
		verifyNoInteractions(labelRepository, assignmentRepository);
	}

	@Test
	void changesNothingInAnArchivedProgram() {
		Program archived = program(PROGRAM_A, true);
		archived.setStatus(ProgramStatus.COMPLETED);
		editable(archived);

		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, List.of()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelsArchived"));
		verifyNoInteractions(assignmentRepository);
	}

	@Test
	void anArchivedProgramStillShowsItsLabelsButNotForEditing() {
		Program archived = program(PROGRAM_A, true);
		archived.setStatus(ProgramStatus.COMPLETED);
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, PROGRAM_A)).thenReturn(archived);
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(label(PROGRAM_A, "AI")));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of());

		MentorLabelResponse.Board board = service.board(COORDINATOR, PROGRAM_A);

		assertThat(board.enabled()).isTrue();
		assertThat(board.editable()).isFalse();
		assertThat(board.labels()).hasSize(1);
	}

	@Test
	void aCoordinatorOfAnotherProgramCannotLabelItsMentors() {
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, PROGRAM_B))
				.thenThrow(new InvalidRequestException("Only the program coordinator can change this program."));

		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_B, MENTOR, List.of()))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.board(COORDINATOR, PROGRAM_B)).isInstanceOf(InvalidRequestException.class);
		verifyNoInteractions(labelRepository, assignmentRepository, programMentorRepository);
	}

	@Test
	void showsAnEmptyBoardWhileTheSwitchIsOff() {
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, PROGRAM_A))
				.thenReturn(program(PROGRAM_A, false));

		assertThat(service.board(COORDINATOR, PROGRAM_A))
				.isEqualTo(new MentorLabelResponse.Board(false, false, List.of(), List.of()));
		verifyNoInteractions(labelRepository, assignmentRepository);
	}

	@Test
	void theBoardCountsCarriersAndLeavesOutMentorsNoLongerInThePool() {
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, PROGRAM_A))
				.thenReturn(program(PROGRAM_A, true));
		ProgramMentorLabel ai = label(PROGRAM_A, "AI");
		ProgramMentorLabel fintech = label(PROGRAM_A, "Fintech");
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(ai, fintech));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of(MENTOR));
		when(assignmentRepository.findByProgramId(PROGRAM_A)).thenReturn(List.of(
				assignment(fintech, MENTOR), assignment(ai, MENTOR), assignment(ai, OTHER_MENTOR)));

		MentorLabelResponse.Board board = service.board(COORDINATOR, PROGRAM_A);

		assertThat(board.enabled()).isTrue();
		assertThat(board.editable()).isTrue();
		assertThat(board.labels()).extracting(MentorLabelResponse.BoardLabel::mentorCount).containsExactly(1, 1);
		assertThat(board.mentors()).containsExactly(
				new MentorLabelResponse.MentorLabels(MENTOR, List.of(ai.getId(), fintech.getId())));
	}

	@Test
	void savingAMentorsLabelsReplacesTheWholeSetOnceEach() {
		editable(program(PROGRAM_A, true));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, MENTOR))
				.thenReturn(Optional.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED)));
		ProgramMentorLabel ai = label(PROGRAM_A, "AI");
		ProgramMentorLabel fintech = label(PROGRAM_A, "Fintech");
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(ai, fintech));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of(MENTOR));

		service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR,
				List.of(fintech.getId(), ai.getId(), fintech.getId()));

		verify(assignmentRepository).deleteByProgramIdAndMentorId(PROGRAM_A, MENTOR);
		@SuppressWarnings("unchecked") // a captor for a generic parameter type
		ArgumentCaptor<List<ProgramMentorLabelAssignment>> saved = ArgumentCaptor.forClass(List.class);
		verify(assignmentRepository).saveAll(saved.capture());
		assertThat(saved.getValue()).extracting(ProgramMentorLabelAssignment::getLabelId)
				.containsExactly(fintech.getId(), ai.getId());
		assertThat(saved.getValue()).allSatisfy(row -> {
			assertThat(row.getMentorId()).isEqualTo(MENTOR);
			assertThat(row.getProgramId()).isEqualTo(PROGRAM_A);
		});
	}

	@Test
	void refusesASixthLabelOnOneMentor() {
		editable(program(PROGRAM_A, true));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, MENTOR))
				.thenReturn(Optional.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED)));
		List<UUID> six = IntStream.range(0, MentorLabelLimits.PER_MENTOR + 1).mapToObj(index -> UUID.randomUUID())
				.toList();

		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, six))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelTooMany", MentorLabelLimits.PER_MENTOR));
		verifyNoInteractions(assignmentRepository);
	}

	@Test
	void refusesALabelFromAnotherProgram() {
		editable(program(PROGRAM_A, true));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, MENTOR))
				.thenReturn(Optional.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED)));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(label(PROGRAM_A, "AI")));
		UUID programBLabel = label(PROGRAM_B, "Fintech").getId();

		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, List.of(programBLabel)))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(assignmentRepository);
	}

	@Test
	void labelsOnlyAMentorWhoIsInThePool() {
		editable(program(PROGRAM_A, true));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, MENTOR))
				.thenReturn(Optional.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.PENDING)));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, OTHER_MENTOR))
				.thenReturn(Optional.empty());

		String notInPool = Texts.forRequest("error.mentorLabelMentorNotInPool");
		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, List.of()))
				.hasMessage(notInPool);
		assertThatThrownBy(() -> service.setMentorLabels(COORDINATOR, PROGRAM_A, OTHER_MENTOR, List.of()))
				.hasMessage(notInPool);
		verifyNoInteractions(assignmentRepository);
	}

	@Test
	void renamingKeepsItsOwnNameFreeAndDeletingTakesTheLabelOffEveryone() {
		editable(program(PROGRAM_A, true));
		ProgramMentorLabel fintech = label(PROGRAM_A, "fintech");
		when(labelRepository.findByIdAndProgramId(fintech.getId(), PROGRAM_A)).thenReturn(Optional.of(fintech));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(fintech));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of());

		service.update(COORDINATOR, PROGRAM_A, fintech.getId(),
				new MentorLabelRequest(null, "Fintech", MentorLabelColor.GREEN));
		assertThat(fintech.getName()).isEqualTo("Fintech");
		assertThat(fintech.getColor()).isEqualTo(MentorLabelColor.GREEN);

		service.delete(COORDINATOR, PROGRAM_A, fintech.getId());
		verify(assignmentRepository).deleteByLabelId(fintech.getId());
		verify(labelRepository).delete(fintech);
	}

	@Test
	void refusesToChangeALabelOfAnotherProgram() {
		editable(program(PROGRAM_A, true));
		UUID programBLabel = UUID.randomUUID();
		when(labelRepository.findByIdAndProgramId(programBLabel, PROGRAM_A)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(COORDINATOR, PROGRAM_A, programBLabel))
				.isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(assignmentRepository);
	}

	@Test
	void aMenteeSeesTheLabelsOfTheirOwnProgramOnly() {
		AppUser mentee = user(MENTEE, UserRole.MENTEE);
		when(appUserRepository.findById(MENTEE)).thenReturn(Optional.of(mentee));
		when(menteeProgramService.programOf(mentee)).thenReturn(Optional.of(PROGRAM_A));
		when(programRepository.findById(PROGRAM_A)).thenReturn(Optional.of(program(PROGRAM_A, true)));
		ProgramMentorLabel ai = label(PROGRAM_A, "AI");
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(ai));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of(MENTOR));
		when(assignmentRepository.findByProgramId(PROGRAM_A)).thenReturn(List.of(assignment(ai, MENTOR)));

		MentorLabelResponse.Directory directory = service.directory(MENTEE);

		assertThat(directory.programId()).isEqualTo(PROGRAM_A);
		assertThat(directory.labels()).containsExactly(
				new MentorLabelResponse.Label(ai.getId(), "AI", MentorLabelColor.BLUE));
		assertThat(directory.mentors()).containsExactly(
				new MentorLabelResponse.MentorLabels(MENTOR, List.of(ai.getId())));
	}

	@Test
	void theDirectoryIsEmptyWhenTheSwitchIsOffOrTheCallerIsNotAnApprovedMentee() {
		AppUser mentee = user(MENTEE, UserRole.MENTEE);
		when(appUserRepository.findById(MENTEE)).thenReturn(Optional.of(mentee));
		when(menteeProgramService.programOf(mentee)).thenReturn(Optional.of(PROGRAM_A));
		when(programRepository.findById(PROGRAM_A)).thenReturn(Optional.of(program(PROGRAM_A, false)));
		AppUser pending = user(UUID.randomUUID(), UserRole.MENTEE);
		pending.setStatus(UserAccountStatus.PENDING);
		when(appUserRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
		when(appUserRepository.findById(MENTOR)).thenReturn(Optional.of(user(MENTOR, UserRole.MENTOR)));

		MentorLabelResponse.Directory empty = new MentorLabelResponse.Directory(null, List.of(), List.of());
		assertThat(service.directory(MENTEE)).isEqualTo(empty);
		assertThat(service.directory(pending.getId())).isEqualTo(empty);
		assertThat(service.directory(MENTOR)).isEqualTo(empty);
		assertThat(service.directory(null)).isEqualTo(empty);
		verifyNoInteractions(labelRepository, assignmentRepository);
	}

	@Test
	void aMenteeOfOneProgramDoesNotSeeTheMentorsLabelsFromAnother() {
		AppUser mentee = user(MENTEE, UserRole.MENTEE);
		when(appUserRepository.findById(MENTEE)).thenReturn(Optional.of(mentee));
		when(menteeProgramService.programOf(mentee)).thenReturn(Optional.of(PROGRAM_A));
		givenMentorInBothProgramsWithLabelsOn();

		assertThat(service.ofMentor(MENTOR, MENTEE)).extracting(MentorLabelResponse.ProgramLabels::programId)
				.containsExactly(PROGRAM_A);
	}

	@Test
	void theMentorSeesEachSwitchedOnProgramWhereTheyCarryALabel() {
		when(appUserRepository.findById(MENTOR)).thenReturn(Optional.of(user(MENTOR, UserRole.MENTOR)));
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(MENTOR)).thenReturn(List.of(
				membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED),
				membership(PROGRAM_B, MENTOR, ProgramMentorStatus.ACCEPTED)));
		givenMentorInBothProgramsWithLabelsOn();

		List<MentorLabelResponse.ProgramLabels> labels = service.ofMentor(MENTOR, MENTOR);

		assertThat(labels).extracting(MentorLabelResponse.ProgramLabels::programName)
				.containsExactly("Build '26", "Growth");
		assertThat(labels.getFirst().labels()).extracting(MentorLabelResponse.Label::name)
				.containsExactly("AI", "Fintech");
	}

	@Test
	void aCoordinatorSeesOnlyTheProgramsTheyManage() {
		AppUser coordinator = user(COORDINATOR, UserRole.COORDINATOR);
		when(appUserRepository.findById(COORDINATOR)).thenReturn(Optional.of(coordinator));
		when(coordinatorProgramService.managedPrograms(coordinator)).thenReturn(List.of(program(PROGRAM_B, true)));
		givenMentorInBothProgramsWithLabelsOn();

		assertThat(service.ofMentor(MENTOR, COORDINATOR)).extracting(MentorLabelResponse.ProgramLabels::programId)
				.containsExactly(PROGRAM_B);
	}

	@Test
	void leavesOutProgramsWithTheSwitchOff() {
		when(appUserRepository.findById(MENTOR)).thenReturn(Optional.of(user(MENTOR, UserRole.MENTOR)));
		when(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(MENTOR))
				.thenReturn(List.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED)));
		when(programMentorRepository.findProgramNamesByMentorIds(List.of(MENTOR), ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(programName(PROGRAM_A, "Build '26")));
		when(programRepository.findAllById(Set.of(PROGRAM_A))).thenReturn(List.of(program(PROGRAM_A, false)));

		assertThat(service.ofMentor(MENTOR, MENTOR)).isEmpty();
		verifyNoInteractions(labelRepository, assignmentRepository);
	}

	@Test
	void answersAClashWithTheDatabaseNameIndexAsNameTaken() {
		editable(program(PROGRAM_A, true));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(label(PROGRAM_A, "izmir")));
		when(labelRepository.saveAndFlush(any()))
				.thenThrow(new DataIntegrityViolationException("uq_program_mentor_label_name"));

		assertThatThrownBy(() -> service.create(COORDINATOR, PROGRAM_A,
				new MentorLabelRequest(null, "İzmir", MentorLabelColor.BLUE)))
				.isInstanceOf(ConflictException.class)
				.hasMessage(Texts.forRequest("error.mentorLabelNameTaken"));
	}

	@Test
	void dropsInvisibleCharactersAndFoldsEveryKindOfSpace() {
		String zeroWidth = Character.toString(0x200B);
		String noBreak = Character.toString(0x00A0);
		String rightToLeft = Character.toString(0x202E);
		String byteOrderMark = Character.toString(0xFEFF);

		assertThat(MentorLabelService.normalizedName("Fin" + zeroWidth + "tech" + noBreak + noBreak + "AI\t"))
				.isEqualTo("Fintech AI");
		assertThat(MentorLabelService.normalizedName("Fin" + byteOrderMark + "tech")).isEqualTo("Fintech");
		assertThatThrownBy(() -> MentorLabelService.normalizedName(zeroWidth + rightToLeft))
				.hasMessage(Texts.forRequest("error.mentorLabelNameRequired"));
	}

	@Test
	void anEmptyListTakesEveryLabelOffTheMentor() {
		editable(program(PROGRAM_A, true));
		when(programMentorRepository.findByProgramIdAndMentorIdForUpdate(PROGRAM_A, MENTOR))
				.thenReturn(Optional.of(membership(PROGRAM_A, MENTOR, ProgramMentorStatus.ACCEPTED)));
		when(labelRepository.findByProgramIdOrderByNameAsc(PROGRAM_A)).thenReturn(List.of(label(PROGRAM_A, "AI")));
		when(menteeProgramService.acceptedMentorIds(PROGRAM_A)).thenReturn(Set.of(MENTOR));

		service.setMentorLabels(COORDINATOR, PROGRAM_A, MENTOR, List.of());

		verify(assignmentRepository).deleteByProgramIdAndMentorId(PROGRAM_A, MENTOR);
		verify(assignmentRepository).saveAll(List.of());
	}

	@Test
	void anAdminSeesTheMentorsLabelsInEveryProgram() {
		AppUser admin = user(UUID.randomUUID(), UserRole.ADMIN);
		when(appUserRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
		when(coordinatorProgramService.managedPrograms(admin))
				.thenReturn(List.of(program(PROGRAM_A, true), program(PROGRAM_B, true)));
		givenMentorInBothProgramsWithLabelsOn();

		assertThat(service.ofMentor(MENTOR, admin.getId())).extracting(MentorLabelResponse.ProgramLabels::programId)
				.containsExactly(PROGRAM_A, PROGRAM_B);
	}

	@Test
	void anotherMentorSeesNoLabels() {
		AppUser other = user(OTHER_MENTOR, UserRole.MENTOR);
		when(appUserRepository.findById(OTHER_MENTOR)).thenReturn(Optional.of(other));
		when(programMentorRepository.findProgramNamesByMentorIds(List.of(MENTOR), ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(programName(PROGRAM_A, "Build '26")));
		when(programRepository.findAllById(Set.of(PROGRAM_A))).thenReturn(List.of(program(PROGRAM_A, true)));

		assertThat(service.ofMentor(MENTOR, OTHER_MENTOR)).isEmpty();
		verifyNoInteractions(labelRepository, assignmentRepository);
	}

	@Test
	void showsNothingToSomeoneWhoIsNotSignedIn() {
		assertThat(service.ofMentor(MENTOR, null)).isEmpty();
		verifyNoInteractions(programMentorRepository, labelRepository, assignmentRepository);
	}

	/** A program the coordinator manages, as the write path loads it: checked, then locked. */
	private void editable(Program program) {
		when(coordinatorProgramService.requireManagedProgram(COORDINATOR, program.getId())).thenReturn(program);
		when(programRepository.findByIdForUpdate(program.getId())).thenReturn(Optional.of(program));
	}

	/** Build '26 (A): AI and Fintech; Growth (B): Pazarlama; both switched on. */
	private void givenMentorInBothProgramsWithLabelsOn() {
		when(programMentorRepository.findProgramNamesByMentorIds(List.of(MENTOR), ProgramMentorStatus.ACCEPTED))
				.thenReturn(List.of(programName(PROGRAM_A, "Build '26"), programName(PROGRAM_B, "Growth")));
		when(programRepository.findAllById(Set.of(PROGRAM_A, PROGRAM_B)))
				.thenReturn(List.of(program(PROGRAM_A, true), program(PROGRAM_B, true)));
		ProgramMentorLabel fintech = label(PROGRAM_A, "Fintech");
		ProgramMentorLabel ai = label(PROGRAM_A, "AI");
		ProgramMentorLabel marketing = label(PROGRAM_B, "Pazarlama");
		List<ProgramMentorLabel> all = List.of(fintech, ai, marketing);
		List<ProgramMentorLabelAssignment> carried = List.of(assignment(fintech, MENTOR), assignment(ai, MENTOR),
				assignment(marketing, MENTOR));
		when(labelRepository.findByProgramIdInOrderByNameAsc(anyList())).thenAnswer(call -> {
			Collection<UUID> ids = call.getArgument(0);
			return all.stream().filter(label -> ids.contains(label.getProgramId())).toList();
		});
		when(assignmentRepository.findByMentorIdAndProgramIdIn(eq(MENTOR), anyList())).thenAnswer(call -> {
			Collection<UUID> ids = call.getArgument(1);
			return carried.stream().filter(row -> ids.contains(row.getProgramId())).toList();
		});
	}

	private static Program program(UUID id, boolean labelsOn) {
		Program program = new Program();
		program.setId(id);
		program.setName(id.equals(PROGRAM_A) ? "Build '26" : "Growth");
		program.setStatus(ProgramStatus.ACTIVE);
		program.setMentorLabelsEnabled(labelsOn);
		return program;
	}

	private static ProgramMentorLabel label(UUID programId, String name) {
		ProgramMentorLabel label = new ProgramMentorLabel();
		label.setId(UUID.randomUUID());
		label.setProgramId(programId);
		label.setName(name);
		label.setColor(MentorLabelColor.BLUE);
		label.setCreatedAt(Instant.now());
		return label;
	}

	private static ProgramMentorLabelAssignment assignment(ProgramMentorLabel label, UUID mentorId) {
		ProgramMentorLabelAssignment assignment = new ProgramMentorLabelAssignment();
		assignment.setId(UUID.randomUUID());
		assignment.setLabelId(label.getId());
		assignment.setProgramId(label.getProgramId());
		assignment.setMentorId(mentorId);
		assignment.setCreatedAt(Instant.now());
		return assignment;
	}

	private static ProgramMentor membership(UUID programId, UUID mentorId, ProgramMentorStatus status) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setMentorId(mentorId);
		membership.setStatus(status);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now());
		return membership;
	}

	private static AppUser user(UUID id, UserRole role) {
		AppUser user = new AppUser();
		user.setId(id);
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setFullName("Kişi");
		user.setEmail(id + "@test.co");
		return user;
	}

	private static MentorProgramName programName(UUID programId, String name) {
		return new MentorProgramName() {
			@Override
			public UUID getMentorId() {
				return MENTOR;
			}

			@Override
			public UUID getProgramId() {
				return programId;
			}

			@Override
			public String getProgramName() {
				return name;
			}

			@Override
			public ProgramStatus getProgramStatus() {
				return ProgramStatus.ACTIVE;
			}

			@Override
			public @Nullable String getRole() {
				return null;
			}
		};
	}
}
