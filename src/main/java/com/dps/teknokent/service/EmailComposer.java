package com.dps.teknokent.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.dps.teknokent.config.EmailCenterSettings;
import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.EmailCampaign;
import com.dps.teknokent.util.EmailDomains;
import com.dps.teknokent.util.EmailHtml;
import com.dps.teknokent.util.Texts;

import lombok.RequiredArgsConstructor;

/** Turns a campaign into the mail one person gets: the text, the footer and where a reply goes. */
@Component
@RequiredArgsConstructor
class EmailComposer {

	private static final String LOGO_PATH = "/email/menton-odtu-teknokent.png";

	private final MailService mailService;
	private final EmailCenterSettings settings;

	/** A coordinator answers their own mails; an admin's are answered at the shared address. */
	String replyTo(AppUser sender) {
		return sender.has(UserRole.ADMIN) ? settings.getReplyTo() : EmailDomains.normalizeEmail(sender.getEmail());
	}

	EmailCenterMailer.Mail mail(EmailCampaign campaign, AppUser sender, String programName, String to,
			@Nullable AppLanguage language, String token) {
		AppLanguage footerLanguage = language == null ? AppLanguage.TR : language;
		String page = mailService.link("/unsubscribe?t=" + token);
		return compose(campaign, sender, campaign.getSubject(),
				Texts.get(footerLanguage, "emailCenter.footer.reason", programName),
				Texts.get(footerLanguage, "emailCenter.footer.unsubscribe"),
				to, page, mailService.link("/api/email-unsubscribe?t=" + token));
	}

	/** To its own author, marked as a test; its unsubscribe link has no token and does nothing. */
	EmailCenterMailer.Mail test(EmailCampaign campaign, AppUser sender, String programName) {
		return compose(campaign, sender, Texts.get(sender, "emailCenter.test.subject", campaign.getSubject()),
				Texts.get(sender, "emailCenter.footer.reason", programName),
				Texts.get(sender, "emailCenter.footer.unsubscribe"),
				EmailDomains.normalizeEmail(sender.getEmail()), mailService.link("/unsubscribe"), null);
	}

	/**
	 * The name stands in for the image while a mail program blocks it; the file is the frontend's, without tracking.
	 */
	private String logo(String name) {
		return "<p><img src=\"" + EmailHtml.escape(mailService.link(LOGO_PATH))
				+ "\" width=\"260\" height=\"70\" alt=\""
				+ EmailHtml.escape(name) + "\" style=\"display:block;border:0;background:#ffffff;color:#374151;"
				+ "font:bold 14px Arial,sans-serif\"></p>";
	}

	private EmailCenterMailer.Mail compose(EmailCampaign campaign, AppUser sender, String subject, String reason,
			String unsubscribe, String to, String page, @Nullable String oneClick) {
		String name = Texts.get(AppLanguage.TR, "emailCenter.footer.sender");
		String html = campaign.getBodyHtml() + "<hr>" + logo(name)
				+ "<p style=\"color:#6b7280;font-size:12px\">"
				+ EmailHtml.escape(reason) + " <a href=\"" + EmailHtml.escape(page) + "\">"
				+ EmailHtml.escape(unsubscribe) + "</a></p>";
		String text = EmailHtml.toText(campaign.getBodyHtml()) + "\n\n--\n" + name + "\n" + reason + "\n"
				+ unsubscribe + ": " + page;
		return new EmailCenterMailer.Mail(to, replyTo(sender), subject, html, text, oneClick);
	}
}
