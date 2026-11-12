package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Map;

/**
 * The API's errors, as {@code {"error": "..."}}. Limited to {@link ApiController}, so
 * the website keeps its error pages ({@link GlobalExceptionHandler}). Same rule as
 * there: only the service exceptions carry their own sentence ({@link UserMessages}),
 * anything else gets a fixed one and its details go to the log.
 */
@RestControllerAdvice(assignableTypes = ApiController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ArticleService.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ArticleService.NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, UserMessages.of(e, "Not found"));
    }

    /** No Kindle address yet: marked so a client can point the person at the settings. */
    @ExceptionHandler(KindleMailService.SetupRequiredException.class)
    public ResponseEntity<Map<String, Object>> setupRequired(KindleMailService.SetupRequiredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", UserMessages.of(e, "Add your Kindle e-mail address in Settings first"),
                        "setup", true));
    }

    @ExceptionHandler(KindleMailService.SendRefusedException.class)
    public ResponseEntity<Map<String, Object>> sendRefused(KindleMailService.SendRefusedException e) {
        return error(HttpStatus.FORBIDDEN, UserMessages.of(e, "Could not send article"));
    }

    @ExceptionHandler(KindleMailService.DeliveryFailedException.class)
    public ResponseEntity<Map<String, Object>> deliveryFailed(KindleMailService.DeliveryFailedException e) {
        return error(HttpStatus.BAD_GATEWAY, UserMessages.of(e, "Could not send article"));
    }

    @ExceptionHandler({IllegalArgumentException.class, SafeHttpClient.FetchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, UserMessages.of(e, "That request could not be handled"));
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            TypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception e, HttpServletRequest request) {
        log.debug("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return error(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.BAD_REQUEST_MESSAGE);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, GlobalExceptionHandler.METHOD_NOT_ALLOWED_MESSAGE);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send the body as application/json");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) throws Exception {
        if (e instanceof AccessDeniedException
                || e instanceof AuthenticationException
                || e instanceof ErrorResponse
                || AnnotatedElementUtils.hasAnnotation(e.getClass(), ResponseStatus.class)
                || DisconnectedClientHelper.isClientDisconnectedException(e)) {
            throw e;
        }
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, GlobalExceptionHandler.SERVER_ERROR_MESSAGE);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
