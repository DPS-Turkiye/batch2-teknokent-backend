package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.entity.MentorshipGoal;

public record MentorshipGoalResponse(
		UUID id,
		String title,
		boolean done,
		int sortOrder) {

	public static MentorshipGoalResponse fromEntity(MentorshipGoal goal) {
		return new MentorshipGoalResponse(
				goal.getId(),
				goal.getTitle(),
				goal.isDone(),
				goal.getSortOrder());
	}
}
