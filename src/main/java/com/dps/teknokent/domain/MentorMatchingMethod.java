package com.dps.teknokent.domain;

/** How a mentor recommendation was ranked; the name is what the X-Matching-Method header and the JSON carry. */
public enum MentorMatchingMethod {
	/** Keyword overlap, used when semantic retrieval is off or fails. */
	DETERMINISTIC,
	/** Embeddings from the AI provider, ranked by pgvector. */
	PGVECTOR_SEMANTIC
}
