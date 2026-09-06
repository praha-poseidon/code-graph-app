package com.poseidon.codegraph.app.adapter.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the bundled React app for browser routes.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/", "/workbench", "/workbench/**"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
