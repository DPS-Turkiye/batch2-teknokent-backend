package com.dps.teknokent.dto.requests;

import java.util.UUID;

/** {@code actorId} names the caller on the proxy's service credentials; a bearer caller is always themselves. */
public record CreateTranscriptUploadRequest(UUID actorId) {
}
