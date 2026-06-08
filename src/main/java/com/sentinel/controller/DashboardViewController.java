package com.sentinel.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Sentinel dashboard SPA at http://localhost:8090
 *
 * Spring Boot automatically serves files from /static/* at their paths,
 * so /static/index.html is available at /index.html.
 * This controller also maps / and /dashboard to index.html explicitly.
 */
@Controller
public class DashboardViewController {

    @GetMapping("/")
    public String root() {
        return "forward:/index.html";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "forward:/index.html";
    }
}
