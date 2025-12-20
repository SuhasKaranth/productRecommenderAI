package com.smartguide.poc.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller to serve the React admin UI
 */
@Controller
public class UIController {

    /**
     * Forward all /ui/** requests to the React app's index.html
     * This allows React Router to handle client-side routing
     */
    @GetMapping({"/ui", "/ui/**"})
    public String forwardToReactApp() {
        return "forward:/index.html";
    }
}
