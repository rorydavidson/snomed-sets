package org.snomed.valuesets.web;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.valuesets.snowstorm.SnowstormException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(SnowstormException.class)
	ProblemDetail handleSnowstorm(SnowstormException e) {
		if (e.isCausedByRequest()) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		}
		log.warn("Snowstorm call failed", e);
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail handleInvalidParameters(ConstraintViolationException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A request parameter is missing or out of range.");
	}
}
