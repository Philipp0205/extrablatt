package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import com.kindlerss.service.DisplayPreferencesService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Maps common service exceptions to error pages. */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ArticleService.NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(ArticleService.NotFoundException ex, HttpServletRequest request, Model model) {
        model.addAttribute("message", ex.getMessage());
        return errorView(request, model);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex, HttpServletRequest request, Model model) {
        model.addAttribute("message", ex.getMessage());
        return errorView(request, model);
    }

    /**
     * An exception view is chosen after the interceptors have had their turn, so
     * the edition has to be applied here as well. A reader who cannot use the
     * standard pages cannot read the standard error page either.
     */
    private static String errorView(HttpServletRequest request, Model model) {
        if (!EditionInterceptor.isAccessible(request)) {
            return "error";
        }
        model.addAttribute("accessibleEdition", true);
        model.addAttribute("display", DisplayPreferencesService.resolved(request));
        model.addAttribute("currentPath", "/topics");
        return "accessible/error";
    }
}
