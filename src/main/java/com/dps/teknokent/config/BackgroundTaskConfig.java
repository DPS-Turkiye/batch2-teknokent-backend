package com.dps.teknokent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BackgroundTaskConfig {

	/**
	 * Runs the AI work that follows a commit (handing a leaver's tasks over, a finished meeting's report) off the
	 * request and scheduler threads, so a call of a minute or more neither holds an HTTP response nor delays the
	 * other scheduled jobs. Two at a time keeps a burst from piling onto the AI provider; closing it on shutdown
	 * lets queued work finish.
	 */
	@Bean(destroyMethod = "close")
	ExecutorService aiTaskExecutor() {
		return Executors.newFixedThreadPool(2, Thread.ofVirtual().name("ai-task-", 0).factory());
	}
}
