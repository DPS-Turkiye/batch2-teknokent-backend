package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.dto.requests.AddProgramEligibleEmailsRequest;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEligibleEmail;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramEligibleEmailRepository;

@ExtendWith(MockitoExtension.class)
class ProgramRosterServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @Mock
    private CoordinatorProgramService coordinatorProgramService;
    @Mock
    private ProgramEligibleEmailRepository eligibleEmailRepository;
    @Mock
    private AppUserRepository appUserRepository;

    @InjectMocks
    private ProgramRosterService service;

    private Program managed(String domain) {
        Program program = new Program();
        program.setId(PROGRAM_ID);
        program.setEligibleEmailDomain(domain);
        // the list is read through the managed-program check, changed through the settings-editor one
        org.mockito.Mockito.lenient().when(coordinatorProgramService.requireManagedProgram(ACTOR_ID, PROGRAM_ID))
                .thenReturn(program);
        org.mockito.Mockito.lenient().when(coordinatorProgramService.requireSettingsEditor(ACTOR_ID, PROGRAM_ID))
                .thenReturn(program);
        return program;
    }

    private static ProgramEligibleEmail row(String email) {
        ProgramEligibleEmail row = new ProgramEligibleEmail();
        row.setId(UUID.randomUUID());
        row.setProgramId(PROGRAM_ID);
        row.setEmail(email);
        row.setCreatedAt(Instant.now());
        return row;
    }

    @Test
    @SuppressWarnings("unchecked")
    void addingNormalizesSkipsDuplicatesAndAddressesAlreadyListed() {
        managed("metu.edu.tr");
        when(eligibleEmailRepository.findByProgramIdAndEmailIn(any(), anyCollection()))
                .thenReturn(List.of(row("e1@metu.edu.tr")));

        service.add(ACTOR_ID, PROGRAM_ID, new AddProgramEligibleEmailsRequest(ACTOR_ID,
                List.of(" E1@metu.edu.tr", "e2@METU.edu.tr", "e2@metu.edu.tr", "  ", "e3@ceng.metu.edu.tr")));

        ArgumentCaptor<List<ProgramEligibleEmail>> saved = ArgumentCaptor.forClass(List.class);
        verify(eligibleEmailRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(ProgramEligibleEmail::getEmail)
                .containsExactly("e2@metu.edu.tr", "e3@ceng.metu.edu.tr");
        assertThat(saved.getValue()).allMatch(entry -> ACTOR_ID.equals(entry.getAddedBy()));
    }

    @Test
    void onePastedBadAddressRefusesTheWholePasteAndNamesIt() {
        managed("metu.edu.tr");

        assertThatThrownBy(() -> service.add(ACTOR_ID, PROGRAM_ID, new AddProgramEligibleEmailsRequest(ACTOR_ID,
                List.of("e1@metu.edu.tr", "ali@gmail.com", "not-an-email"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("ali@gmail.com")
                .hasMessageContaining("not-an-email");
        verify(eligibleEmailRepository, never()).saveAll(any());
    }

    @Test
    void theListShowsWhoAlreadyHasAnAccount() {
        managed(null);
        when(eligibleEmailRepository.findByProgramIdOrderByEmailAsc(PROGRAM_ID))
                .thenReturn(List.of(row("a@metu.edu.tr"), row("b@metu.edu.tr")));
        when(appUserRepository.findRegisteredEmails(anyCollection())).thenReturn(List.of("b@metu.edu.tr"));

        var rows = service.list(ACTOR_ID, PROGRAM_ID);

        assertThat(rows).extracting(entry -> entry.email() + ":" + entry.registered())
                .containsExactly("a@metu.edu.tr:false", "b@metu.edu.tr:true");
    }

    @Test
    void removingAnEntryOfAnotherProgramIsNotFound() {
        managed(null);
        UUID entryId = UUID.randomUUID();
        when(eligibleEmailRepository.findByIdAndProgramId(entryId, PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(ACTOR_ID, PROGRAM_ID, entryId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
