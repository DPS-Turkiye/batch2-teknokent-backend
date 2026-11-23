package com.dps.teknokent.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.web.util.HtmlUtils;

/** The formatted text of an email-center mail: what may be in it, and its plain-text twin. */
public final class EmailHtml {

	private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
			.allowElements("p", "br", "strong", "b", "em", "i", "u", "ul", "ol", "li", "h2", "h3", "a")
			.allowAttributes("href").onElements("a")
			.allowUrlProtocols("https", "mailto")
			.toFactory();

	private static final Pattern LINK = Pattern.compile("<a\\b[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
	private static final Pattern LINE_END = Pattern.compile("<br\\s*/?>|</(p|li|h2|h3|ul|ol)>");
	private static final Pattern ITEM = Pattern.compile("<li>");
	private static final Pattern TAG = Pattern.compile("<[^>]*>");
	private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

	private EmailHtml() {
	}

	public static String sanitize(String html) {
		return POLICY.sanitize(html);
	}

	/** {@code sanitized} must come from {@link #sanitize}. A link keeps its address after its text. */
	public static String toText(String sanitized) {
		String text = LINK.matcher(sanitized).replaceAll(match -> {
			String href = match.group(1);
			String label = match.group(2);
			return Matcher.quoteReplacement(
					href.equals(label) ? label : label + " (" + href + ")");
		});
		text = ITEM.matcher(text).replaceAll("- ");
		text = LINE_END.matcher(text).replaceAll("\n");
		text = TAG.matcher(text).replaceAll("");
		text = HtmlUtils.htmlUnescape(text);
		return BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
	}

	/** One line of {@code text} as it may stand in HTML. */
	public static String escape(String text) {
		return HtmlUtils.htmlEscape(text, "UTF-8");
	}
}
