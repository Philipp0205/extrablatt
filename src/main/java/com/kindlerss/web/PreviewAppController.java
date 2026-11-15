package com.kindlerss.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The client-rendered preview app. The page is only a shell: the scripts under
 * {@code /js/preview} read everything from {@code /api/v1} and route by URL hash, so
 * the server needs no other route. It sits behind the website's sign-in like every
 * other page, and nothing links to it.
 */
@Controller
public class PreviewAppController {

    @GetMapping("/app")
    public String app() {
        return "preview-app";
    }
}
