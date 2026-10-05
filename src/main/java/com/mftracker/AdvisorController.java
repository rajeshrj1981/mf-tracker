package com.mftracker;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/advisor")
public class AdvisorController {
    private final AdvisorService advisor;

    public AdvisorController(AdvisorService advisor) { this.advisor = advisor; }

    /** Today's suggestion (created on first request each day, then kept). */
    @GetMapping("/today")
    public AdvisorService.Today today(HttpSession session) { return advisor.today(false, owner(session)); }

    /** Recompute today's suggestion with fresh NAV data. */
    @PostMapping("/today/refresh")
    public AdvisorService.Today refresh(HttpSession session) { return advisor.today(true, owner(session)); }

    @GetMapping("/history")
    public List<AdvisorService.HistoryRow> history(HttpSession session) { return advisor.history(owner(session)); }
    private static String owner(HttpSession session) { return (String) session.getAttribute("mftracker.user"); }
}
