package com.dps.teknokent.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class LocalPiiRedactionService {
	private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
	static {
		PATTERNS.put("email", Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"));
		PATTERNS.put("phone", Pattern.compile("(?<!\\d)(?:\\+?90[ .-]?)?(?:\\(?0?5\\d{2}\\)?[ .-]?)?\\d{3}[ .-]?\\d{2}[ .-]?\\d{2}(?!\\d)"));
		PATTERNS.put("turkish_id", Pattern.compile("(?<!\\d)[1-9]\\d{10}(?!\\d)"));
	}

	public RedactionResult redact(String input) {
		String redacted = input;
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
			Matcher matcher = entry.getValue().matcher(redacted);
			int count = 0;
			StringBuffer output = new StringBuffer();
			while (matcher.find()) {
				count++;
				matcher.appendReplacement(output, "[REDACTED_" + entry.getKey().toUpperCase() + "]");
			}
			matcher.appendTail(output);
			redacted = output.toString();
			counts.put(entry.getKey(), count);
		}
		return new RedactionResult(redacted, counts, counts.values().stream().mapToInt(Integer::intValue).sum());
	}

	public record RedactionResult(String text, Map<String, Integer> redactedByType, int totalRedacted) {}
}
