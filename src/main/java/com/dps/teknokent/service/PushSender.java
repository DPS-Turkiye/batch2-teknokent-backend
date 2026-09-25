package com.dps.teknokent.service;

import java.util.List;

import com.dps.teknokent.dto.provider.PushMessage;

public interface PushSender {
	void send(List<String> tokens, PushMessage message);
}
