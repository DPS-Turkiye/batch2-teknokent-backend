ALTER TABLE mentorship_request DROP CONSTRAINT chk_mentorship_request_status;
ALTER TABLE mentorship_request ADD CONSTRAINT chk_mentorship_request_status
    CHECK (status IN (
        'PENDING',
        'MORE_INFO_REQUESTED',
        'COUNTER_PROPOSED',
        'ACCEPTED',
        'DECLINED',
        'WITHDRAWN'
    ));
