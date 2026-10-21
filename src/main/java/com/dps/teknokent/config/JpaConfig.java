package com.dps.teknokent.config;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every relation is lazy, so a list that reads a relation per row would issue one query per row. Batch fetching
 * loads those in groups instead. Set here, not in application.yml, because the test profile replaces that file.
 */
@Configuration
public class JpaConfig {

	private static final int BATCH_FETCH_SIZE = 50;

	@Bean
	HibernatePropertiesCustomizer batchFetchSize() {
		return properties -> properties.put(AvailableSettings.DEFAULT_BATCH_FETCH_SIZE, BATCH_FETCH_SIZE);
	}
}
