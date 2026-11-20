package com.dps.teknokent.domain;

/** Why a person may not delete their own account. */
public enum AccountDeletionBlock {
	/** Admins and coordinators are removed by an admin, never by themselves. */
	STAFF,
	/** They still lead a startup; it has to be deleted first. */
	FOUNDER,
	/** The account is tied to ODTÜ institution records, which deletion does not support yet. */
	INSTITUTION
}
