package com.dps.teknokent.dto.responses;

/** The program and its weekly plan after a weekly program was saved. */
public record WeeklyProgramSavedResponse(CoordinatorProgramResponse.Detail program, ProgramWeekPlanResponse plan) {
}
