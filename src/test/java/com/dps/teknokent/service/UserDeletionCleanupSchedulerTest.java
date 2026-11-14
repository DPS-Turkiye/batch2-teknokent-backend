package com.dps.teknokent.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;

class UserDeletionCleanupSchedulerTest {

	@Test
	void eachTickRetriesTheCleanupsThatAreDue() {
		UserDeletionCleanupService cleanupService = mock(UserDeletionCleanupService.class);

		new UserDeletionCleanupScheduler(cleanupService).retryDueCleanups();

		verify(cleanupService).retryDue();
		verifyNoMoreInteractions(cleanupService);
	}
}
