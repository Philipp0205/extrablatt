package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.ui.Model;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Map;

/**
 * Maps exceptions to the error page.
 *
 * <p>Only the service exceptions below carry a message meant for the reader. Anything
 * else is answered with a fixed sentence: a framework or driver message can name
 * classes, SQL or file paths, none of which belong on a page. The details go to the
 * log instead.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String BAD_REQUEST_MESSAGE = "That request was incomplete or not valid.";
    static final String NOT_FOUND_MESSAGE = "Not found";
    static final String METHOD_NOT_ALLOWED_MESSAGE = "That page cannot be used this way.";
    static final String SERVER_ERROR_MESSAGE = "Something went wrong on our side. Please try again.";

    @ExceptionHandler(ArticleService.NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(ArticleService.NotFoundException ex, Model model) {
        return error(model, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex, Model model) {
        return error(model, UserMessages.of(ex, "That request could not be handled"));
    }

    /** Missing, malformed or invalid input. The framework's own wording stays in the log. */
    @ExceptionHandler({
            BindException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MissingRequestHeaderException.class,
            TypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalidRequest(Exception ex, HttpServletRequest request, Model model) {
        log.debug("Rejected {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return error(model, BAD_REQUEST_MESSAGE);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Object noSuchPage(Exception ex, HttpServletRequest request, Model model) {
        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", NOT_FOUND_MESSAGE));
        }
        return error(model, NOT_FOUND_MESSAGE);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Object methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
                                   HttpServletResponse response, Model model) {
        String[] supported = ex.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            response.setHeader(HttpHeaders.ALLOW, String.join(", ", supported));
        }
        if (isApiRequest(request)) {
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(Map.of("error", METHOD_NOT_ALLOWED_MESSAGE));
        }
        return error(model, METHOD_NOT_ALLOWED_MESSAGE);
    }

    /**
     * Failures that happen before a controller is picked (no such path, wrong method)
     * cannot be caught by {@link ApiExceptionHandler}, so these two answer the API in JSON.
     */
    private static boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/");
    }

    /**
     * Everything nobody planned for: logged in full, answered with a 500 that says
     * nothing about the cause.
     *
     * <p>Some exceptions are not ours to answer and are thrown back, which hands
     * them on to the next resolver: Spring Security turns access and authentication
     * failures into a login redirect or a 403, an exception that declares its own
     * status ({@code ResponseStatusException}, {@code @ResponseStatus}) keeps it,
     * and a reader who closed the tab needs no error page at all.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception ex, HttpServletRequest request, Model model) throws Exception {
        if (ex instanceof AccessDeniedException
                || ex instanceof AuthenticationException
                || ex instanceof ErrorResponse
                || AnnotatedElementUtils.hasAnnotation(ex.getClass(), ResponseStatus.class)
                || DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            throw ex;
        }
        // The query string is left out: it can carry reset tokens or e-mail addresses.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(model, SERVER_ERROR_MESSAGE);
    }

    private String error(Model model, String message) {
        model.addAttribute("message", message);
        return "error";
    }
}
