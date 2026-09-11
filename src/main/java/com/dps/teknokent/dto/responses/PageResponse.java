package com.dps.teknokent.dto.responses;

import java.util.List;

import org.springframework.data.domain.Page;

public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(
				page.getContent(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}

	public static <T> PageResponse<T> of(
			List<T> content, int page, int size, long totalElements) {
		int totalPages = size <= 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
		return new PageResponse<>(content, page, size, totalElements, totalPages);
	}
}
