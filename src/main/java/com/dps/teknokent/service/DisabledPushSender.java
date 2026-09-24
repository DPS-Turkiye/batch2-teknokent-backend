package com.dps.teknokent.service;

import java.util.List;

import com.dps.teknokent.dto.provider.PushMessage;

public class DisabledPushSender implements PushSender {

	@Override
	public void send(List<String> tokens, PushMessage message) {
	}
}
