package com.kindlerss.web;

import com.kindlerss.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.stereotype.Controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * What a reader sees when a request fails. Standalone, so each case is one throw in
 * a controller and the assertion is about the handler alone; the rendered page is
 * covered against the whole application in {@code ApplicationContextSmokeTest}.
 */
class GlobalExceptionHandlerTest {

    private static final String SECRET = "jdbc:postgresql://db.internal:5432/prod password=hunter2";

    private MockMvc mockMvc;

    @Controller
    static class FailingController {

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException(SECRET);
        }

        @GetMapping("/gone")
        String gone() {
            throw new ArticleService.NotFoundException("Article not found");
        }

        @GetMapping("/bad")
        String bad() {
            throw new IllegalArgumentException("Choose a category to rename");
        }

        @GetMapping("/items/{id}")
        @ResponseBody
        String item(@PathVariable("id") long id) {
            return "item " + id;
        }

        @PostMapping("/feeds")
        @ResponseBody
        String addFeed(@RequestParam("url") String url) {
            return url;
        }

        @GetMapping("/missing-file")
        String missingFile() throws Exception {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-file");
        }

        @GetMapping("/teapot")
        String teapot() {
            throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT);
        }

        @GetMapping("/forbidden")
        String forbidden() {
            throw new AccessDeniedException("nope");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(new StylesheetAdvice()))
                .build();
    }

    @Test
    void anUnexpectedFailureIsA500ThatGivesNothingAway() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", GlobalExceptionHandler.SERVER_ERROR_MESSAGE))
                .andExpect(model().attribute("message", not(containsString("hunter2"))));
    }

    @Test
    void theErrorPageGetsTheStylesheetEvenFromAnExceptionHandler() throws Exception {
        mockMvc.perform(get("/boom"))
                .andExpect(model().attribute("stylesheet", containsString("box-sizing")));
    }

    @Test
    void aServiceNotFoundKeepsItsOwnMessage() throws Exception {
        mockMvc.perform(get("/gone"))
                .andExpect(status().isNotFound())
                .andExpect(model().attribute("message", "Article not found"));
    }

    @Test
    void aServiceRefusalKeepsItsOwnMessage() throws Exception {
        mockMvc.perform(get("/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("message", "Choose a category to rename"));
    }

    @Test
    void aMalformedPathVariableIsABadRequest() throws Exception {
        mockMvc.perform(get("/items/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", GlobalExceptionHandler.BAD_REQUEST_MESSAGE));
    }

    @Test
    void aMissingParameterIsABadRequest() throws Exception {
        mockMvc.perform(post("/feeds"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", GlobalExceptionHandler.BAD_REQUEST_MESSAGE));
    }

    @Test
    void aMissingStaticResourceIsNotFound() throws Exception {
        mockMvc.perform(get("/missing-file"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", GlobalExceptionHandler.NOT_FOUND_MESSAGE));
    }

    @Test
    void anUnknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"));
    }

    @Test
    void theWrongMethodIs405AndNamesTheRightOne() throws Exception {
        mockMvc.perform(put("/feeds").param("url", "https://example.com/feed"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(view().name("error"))
                .andExpect(model().attribute("message", GlobalExceptionHandler.METHOD_NOT_ALLOWED_MESSAGE));
    }

    /** An exception that names its own status is not flattened into a 500. */
    @Test
    void anExceptionWithItsOwnStatusKeepsIt() throws Exception {
        mockMvc.perform(get("/teapot"))
                .andExpect(status().isIAmATeapot());
    }

    /**
     * Access failures belong to Spring Security, which turns them into a login
     * redirect or a 403 further out; the handler must let them through.
     */
    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        Exception thrown = assertThrows(Exception.class, () -> mockMvc.perform(get("/forbidden")));
        assertInstanceOf(AccessDeniedException.class, thrown.getCause());
    }
}
