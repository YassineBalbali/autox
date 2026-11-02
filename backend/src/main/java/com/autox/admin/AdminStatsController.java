package com.autox.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin dashboard. Administrators only, by the rule on {@code /api/v1/admin/**} in
 * {@code SecurityConfig} and by the {@code @PreAuthorize} here, as on the other admin controllers:
 * 401 without a session, 403 with any other role.
 */
@RestController
@RequestMapping("/api/v1/admin/stats")
@Tag(name = "Admin", description = "Moderation. Requires the ADMIN role.")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    /** Private: these figures are for administrators, and no shared cache may hold them. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate();

    private final AdminStatsService stats;

    public AdminStatsController(AdminStatsService stats) {
        this.stats = stats;
    }

    /**
     * Figures for the dashboard, each split into real activity and demo seed. At most about a
     * minute old: see {@code generatedAt}.
     */
    @GetMapping
    public ResponseEntity<AdminStats> stats() {
        return ResponseEntity.ok().cacheControl(CACHE).body(stats.stats());
    }
}
