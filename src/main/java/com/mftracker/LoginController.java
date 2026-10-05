package com.mftracker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
class LoginController {
    @Value("${mftracker.auth.rajesh-password}")
    private String rajeshPassword;

    @Value("${mftracker.auth.cams-password}")
    private String camsPassword;

    record Credentials(String username, String password) { }

    @PostMapping("/login")
    Map<String, String> login(@RequestBody Credentials credentials, HttpServletRequest request) {
        String username = credentials == null || credentials.username() == null ? "" : credentials.username().trim().toLowerCase(Locale.ROOT);
        String password = credentials == null || credentials.password() == null ? "" : credentials.password().trim();
        String expectedPassword = switch (username) {
            case "rajesh" -> rajeshPassword;
            case "cams" -> camsPassword;
            default -> null;
        };
        if (expectedPassword == null || expectedPassword.isBlank() || !java.security.MessageDigest.isEqual(expectedPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8), password.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        request.getSession(true).setAttribute("mftracker.user", username);
        return Map.of("username", username);
    }

    @GetMapping("/session")
    Map<String, String> session(HttpSession session) {
        Object user = session.getAttribute("mftracker.user");
        if (user == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in");
        return Map.of("username", user.toString());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpSession session) { session.invalidate(); }
}

@org.springframework.stereotype.Component
class LoginFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/auth/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getSession(false) == null || request.getSession(false).getAttribute("mftracker.user") == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Please log in\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
