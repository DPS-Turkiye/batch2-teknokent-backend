package com.dps.teknokent.repository;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.ProgramStatus;

public interface MentorProgramName {

	UUID getMentorId();

	UUID getProgramId();

	String getProgramName();

	ProgramStatus getProgramStatus();

	/** Optional: NULL when no role was given for the mentor in that program. */
	@Nullable
	String getRole();
}
