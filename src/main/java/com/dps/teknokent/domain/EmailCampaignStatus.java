package com.dps.teknokent.domain;

/** An email-center mail before, during and after its sending. */
public enum EmailCampaignStatus {
	DRAFT,
	/** Confirmed: in its hold, or leaving. */
	SENDING,
	SENT,
	/** Taken back by its sender or an admin; never resumed. */
	STOPPED
}
