package com.dps.teknokent.repository;

import java.util.UUID;

import com.dps.teknokent.domain.ProgramStatus;

public interface MentorProgramName {

	UUID getMentorId();

	UUID getProgramId();

	String getProgramName();

	ProgramStatus getProgramStatus();

	String getRole();
}
