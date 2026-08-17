package com.dps.teknokent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextUtilsTest {

	@Test
	void normalizesOptionalAndComparisonText() {
		assertThat(TextUtils.normalizeOptional("  hello  ")).isEqualTo("hello");
		assertThat(TextUtils.normalizeOptional("   ")).isNull();
		assertThat(TextUtils.normalizeOptional(null)).isNull();
		assertThat(TextUtils.normalizeForComparison(" Next.js ")).isEqualTo("nextjs");
		assertThat(TextUtils.normalizeForComparison("İş Modeli")).isEqualTo("işmodeli");
	}
}
