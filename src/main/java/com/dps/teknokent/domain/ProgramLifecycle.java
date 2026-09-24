package com.dps.teknokent.domain;

/**
 * Where a program stands for its coordinator. Derived from {@link ProgramStatus} and the start date,
 * so an active program that has not started yet reads as upcoming.
 */
public enum ProgramLifecycle {
	UPCOMING,
	ACTIVE,
	COMPLETED
}
