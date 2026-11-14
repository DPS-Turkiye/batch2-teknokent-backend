package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfficeHoursRequestActionTest {

    @Test
    void theAcademicAnswersWhileTheRequestWaitsForThem() {
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.INSTRUCTOR, OfficeHoursRequestStatus.PENDING))
                .containsExactly(OfficeHoursRequestAction.ACCEPT, OfficeHoursRequestAction.DECLINE,
                        OfficeHoursRequestAction.PROPOSE_TIMES, OfficeHoursRequestAction.ASK_QUESTION);
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.INSTRUCTOR,
                OfficeHoursRequestStatus.MORE_INFO_REQUESTED)).contains(OfficeHoursRequestAction.ACCEPT);
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.INSTRUCTOR,
                OfficeHoursRequestStatus.COUNTER_PROPOSED)).isEmpty();
    }

    @Test
    void theStudentRepliesTakesAnOfferedTimeOrWithdrawsWhileItIsOpen() {
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.STUDENT, OfficeHoursRequestStatus.PENDING))
                .containsExactly(OfficeHoursRequestAction.WITHDRAW);
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.STUDENT,
                OfficeHoursRequestStatus.MORE_INFO_REQUESTED))
                .containsExactly(OfficeHoursRequestAction.REPLY, OfficeHoursRequestAction.WITHDRAW);
        assertThat(OfficeHoursRequestAction.allowedFor(OfficeHoursRole.STUDENT,
                OfficeHoursRequestStatus.COUNTER_PROPOSED))
                .containsExactly(OfficeHoursRequestAction.ACCEPT_PROPOSED, OfficeHoursRequestAction.WITHDRAW);
    }

    @Test
    void nothingIsLeftOnceItIsClosed() {
        for (OfficeHoursRequestStatus status : new OfficeHoursRequestStatus[] { OfficeHoursRequestStatus.ACCEPTED,
                OfficeHoursRequestStatus.DECLINED, OfficeHoursRequestStatus.WITHDRAWN }) {
            for (OfficeHoursRole role : OfficeHoursRole.values()) {
                assertThat(OfficeHoursRequestAction.allowedFor(role, status)).isEmpty();
            }
        }
    }
}
