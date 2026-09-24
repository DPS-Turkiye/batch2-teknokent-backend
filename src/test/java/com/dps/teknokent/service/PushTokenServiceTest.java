package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.PushToken;
import com.dps.teknokent.repository.PushTokenRepository;

@ExtendWith(MockitoExtension.class)
class PushTokenServiceTest {

	private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Mock
	private PushTokenRepository pushTokenRepository;

	@Mock
	private DomainLookupService domainLookupService;

	@InjectMocks
	private PushTokenService pushTokenService;

	@Test
	void registerStoresANewTokenForTheUser() {
		AppUser user = user(USER_ID);
		when(pushTokenRepository.findByToken("fcm-token")).thenReturn(Optional.empty());
		when(domainLookupService.requireUser(USER_ID)).thenReturn(user);
		ArgumentCaptor<PushToken> saved = ArgumentCaptor.forClass(PushToken.class);

		pushTokenService.register(USER_ID, " fcm-token ", "Mozilla/5.0 (iPhone)");

		verify(pushTokenRepository).save(saved.capture());
		assertThat(saved.getValue().getId()).isNotNull();
		assertThat(saved.getValue().getToken()).isEqualTo("fcm-token");
		assertThat(saved.getValue().getUser()).isSameAs(user);
		assertThat(saved.getValue().getUserAgent()).isEqualTo("Mozilla/5.0 (iPhone)");
		assertThat(saved.getValue().getLastSeenAt()).isNotNull();
	}

	@Test
	void registerMovesATokenToTheAccountNowSignedInOnThatBrowser() {
		PushToken existing = new PushToken();
		existing.setId(UUID.randomUUID());
		existing.setToken("fcm-token");
		existing.setUser(user(UUID.randomUUID()));
		AppUser newOwner = user(USER_ID);
		when(pushTokenRepository.findByToken("fcm-token")).thenReturn(Optional.of(existing));
		when(domainLookupService.requireUser(USER_ID)).thenReturn(newOwner);

		pushTokenService.register(USER_ID, "fcm-token", null);

		verify(pushTokenRepository).save(existing);
		assertThat(existing.getUser()).isSameAs(newOwner);
	}

	@Test
	void registerCutsAnOverlongUserAgent() {
		when(pushTokenRepository.findByToken("fcm-token")).thenReturn(Optional.empty());
		when(domainLookupService.requireUser(USER_ID)).thenReturn(user(USER_ID));
		ArgumentCaptor<PushToken> saved = ArgumentCaptor.forClass(PushToken.class);

		pushTokenService.register(USER_ID, "fcm-token", "x".repeat(800));

		verify(pushTokenRepository).save(saved.capture());
		assertThat(saved.getValue().getUserAgent()).hasSize(500);
	}

	@Test
	void unregisterOnlyRemovesTheCallersOwnToken() {
		pushTokenService.unregister(USER_ID, " fcm-token ");

		verify(pushTokenRepository).deleteByTokenAndUserId("fcm-token", USER_ID);
	}

	private static AppUser user(UUID id) {
		AppUser user = new AppUser();
		user.setId(id);
		return user;
	}
}
