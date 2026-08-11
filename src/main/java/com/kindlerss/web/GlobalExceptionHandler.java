package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
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
    public String notFound(ArticleService.NotFoundException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "error";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "error";
    }
}
