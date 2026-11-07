package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramEligibleEmailRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;

@ExtendWith(MockitoExtension.class)
class ProgramEligibilityServiceTest {

    private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-00000000a002");

    @Mock
    private ProgramRepository programRepository;
    @Mock
    private ProgramEligibleEmailRepository eligibleEmailRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private ProgramStartupRepository programStartupRepository;

    @InjectMocks
    private ProgramEligibilityService service;

    private static Program program(String domain, boolean rosterOnly) {
        Program program = new Program();
        program.setId(PROGRAM_ID);
        program.setName("İŞKUR");
        program.setEligibleEmailDomain(domain);
        program.setRosterOnly(rosterOnly);
        return program;
    }

    private static AppUser user(String email) {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        return user;
    }

    @Test
    void anOpenProgramLetsEveryoneInWithoutReadingTheList() {
        assertThatCode(() -> service.requirePerson(program(null, false), "ayse@gmail.com")).doesNotThrowAnyException();
        assertThatCode(() -> service.requireTeam(program(null, false), TEAM_ID)).doesNotThrowAnyException();
        verifyNoInteractions(eligibleEmailRepository, appUserRepository);
    }

    @Test
    void noProgramMeansNoCheck() {
        assertThatCode(() -> service.requirePerson((UUID) null, "ayse@gmail.com")).doesNotThrowAnyException();
        verifyNoInteractions(programRepository);
    }

    @Test
    void aDomainProgramRefusesOtherAddressesAndAcceptsSubdomains() {
        Program metu = program("metu.edu.tr", false);

        assertThatCode(() -> service.requirePerson(metu, "Ali@ceng.METU.edu.tr")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePerson(metu, "ali@gmail.com"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("ali@gmail.com")
                .hasMessageContaining("@metu.edu.tr");
    }

    @Test
    void aRosterProgramNeedsTheDomainAndTheList() {
        Program iskur = program("metu.edu.tr", true);
        when(eligibleEmailRepository.existsByProgramIdAndEmail(PROGRAM_ID, "e1@metu.edu.tr")).thenReturn(true);
        when(eligibleEmailRepository.existsByProgramIdAndEmail(PROGRAM_ID, "e2@metu.edu.tr")).thenReturn(false);

        assertThatCode(() -> service.requirePerson(iskur, " E1@metu.edu.tr")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePerson(iskur, "e2@metu.edu.tr"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("e2@metu.edu.tr");
        assertThatThrownBy(() -> service.requirePerson(iskur, "e1@gmail.com"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aTeamJoinsOnlyWhenEveryMemberQualifies() {
        Program metu = program("metu.edu.tr", false);
        when(appUserRepository.findByOrganizationIdOrderByFullNameAsc(TEAM_ID))
                .thenReturn(List.of(user("a@metu.edu.tr"), user("b@gmail.com")));

        assertThatThrownBy(() -> service.requireTeam(metu, TEAM_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("b@gmail.com");
    }

    @Test
    void someoneJoiningATeamMustQualifyForEachOfItsPrograms() {
        ProgramStartup row = new ProgramStartup();
        row.setProgramId(PROGRAM_ID);
        row.setOrganizationId(TEAM_ID);
        when(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(TEAM_ID)).thenReturn(List.of(row));
        when(programRepository.findAllById(any())).thenReturn(List.of(program("metu.edu.tr", false)));

        assertThatThrownBy(() -> service.requireTeamMember(TEAM_ID, "c@gmail.com"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> service.requireTeamMember(TEAM_ID, "c@metu.edu.tr")).doesNotThrowAnyException();
    }

    @Test
    void requirePersonByIdLoadsTheProgram() {
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program("metu.edu.tr", false)));

        assertThatThrownBy(() -> service.requirePerson(PROGRAM_ID, "ali@gmail.com"))
                .isInstanceOf(InvalidRequestException.class);
    }
}
