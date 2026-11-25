package com.dps.teknokent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.AppUser;

/** The e-mail next to a match's or a meeting proposal's in-app notification. Off unless the deployment turns it on. */
@Service
public class MatchProposalMailService {

	private final NotificationMailer mailer;
	private final boolean enabled;

	public MatchProposalMailService(
			NotificationMailer mailer,
			@Value("${app.mail.match-proposal.enabled:false}") boolean enabled) {
		this.mailer = mailer;
		this.enabled = enabled;
	}

	/** See {@link NotificationMailer#send}. */
	public void send(AppUser reader, NotificationCategory category, String key, String path, Object... args) {
		if (enabled) {
			mailer.send(reader, category, key, path, args);
		}
	}
}
