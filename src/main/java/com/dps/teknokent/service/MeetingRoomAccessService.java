package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.repository.MeetingRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingRoomAccessService {

	private final MeetingRepository meetingRepository;

	@Transactional(readOnly = true)
	public boolean isMeetingVisibleTo(UUID meetingId, UUID organizationId) {
		return meetingRepository.existsByIdAndOrganizationId(meetingId, organizationId);
	}
}
