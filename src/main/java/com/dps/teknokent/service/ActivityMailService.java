package com.dps.teknokent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.entity.AppUser;

/** E-mails about requests and messages. Off unless the deployment turns them on. */
@Service
public class ActivityMailService {

	private final NotificationMailer mailer;
	private final boolean enabled;

	public ActivityMailService(
			NotificationMailer mailer,
			@Value("${app.mail.activity.enabled:false}") boolean enabled) {
		this.mailer = mailer;
		this.enabled = enabled;
	}

	public void send(AppUser reader, NotificationCategory category, String key, String path, Object... args) {
		if (enabled) {
			mailer.send(reader, category, key, path, args);
		}
	}
}
