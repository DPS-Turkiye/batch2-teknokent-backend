package com.dps.teknokent.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.dto.responses.ApiErrorResponse;
import com.dps.teknokent.util.Texts;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ApiErrorResponse handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
		return response(HttpStatus.NOT_FOUND, exception.getMessage(), request, Map.of());
	}

	@ExceptionHandler(InvalidRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiErrorResponse handleInvalidRequest(InvalidRequestException exception, HttpServletRequest request) {
		return new ApiErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				HttpStatus.BAD_REQUEST.getReasonPhrase(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of(),
				exception.code());
	}

	@ExceptionHandler(ConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiErrorResponse handleConflict(ConflictException exception, HttpServletRequest request) {
		return new ApiErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				HttpStatus.CONFLICT.getReasonPhrase(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of(),
				exception.code());
	}

	/**
	 * A write the database refused, most often two requests racing past the same uniqueness check. Its message
	 * names tables, columns and values, so the caller gets a fixed text and the detail stays in the log.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public ApiErrorResponse handleDataIntegrityViolation(DataIntegrityViolationException exception,
			HttpServletRequest request) {
		log.warn("The database refused a write on {} {}: {}", request.getMethod(), request.getRequestURI(),
				exception.getMostSpecificCause().getMessage());
		return response(HttpStatus.CONFLICT, Texts.forRequest("error.dataConflict"), request, Map.of());
	}

	/** The provider's answer names internal hosts and uses its own wording, so it stays in the log. */
	@ExceptionHandler(MeetingProviderException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public ApiErrorResponse handleMeetingProvider(MeetingProviderException exception, HttpServletRequest request) {
		if (exception.getCause() instanceof RestClientResponseException answer) {
			log.warn("{} on {}: the meeting provider answered {} {}", exception.getMessage(), request.getRequestURI(),
					answer.getStatusCode(), answer.getResponseBodyAsString());
		} else {
			log.warn("{} on {}", exception.getMessage(), request.getRequestURI(), exception);
		}
		return new ApiErrorResponse(
				Instant.now(),
				HttpStatus.BAD_GATEWAY.value(),
				HttpStatus.BAD_GATEWAY.getReasonPhrase(),
				Texts.forRequest("error.meetingProviderUnavailable"),
				request.getRequestURI(),
				Map.of(),
				MeetingProviderException.CODE);
	}

	@ExceptionHandler(AiFeatureDisabledException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public ApiErrorResponse handleAiFeatureDisabled(AiFeatureDisabledException exception, HttpServletRequest request) {
		return new ApiErrorResponse(
				Instant.now(),
				HttpStatus.FORBIDDEN.value(),
				HttpStatus.FORBIDDEN.getReasonPhrase(),
				Texts.forRequest("error.aiFeatureDisabled"),
				request.getRequestURI(),
				Map.of(),
				AiFeatureDisabledException.CODE);
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiErrorResponse> handleStatus(ResponseStatusException exception,
			HttpServletRequest request) {
		HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
		return ResponseEntity.status(status).body(response(status, exception.getReason(), request, Map.of()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiErrorResponse handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return response(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ApiErrorResponse handleUnreadableMessage(
			HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return response(
				HttpStatus.BAD_REQUEST,
				"Request body is malformed or contains an unsupported value",
				request,
				Map.of());
	}

	private ApiErrorResponse response(
			HttpStatus status,
			@Nullable String message,
			HttpServletRequest request,
			Map<String, String> fieldErrors) {
		return new ApiErrorResponse(
				Instant.now(),
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI(),
				fieldErrors);
	}
}
