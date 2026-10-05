package com.autox.admin;

import com.autox.api.PageResponse;
import com.autox.user.AppUserRepository;
import com.autox.user.UserRole;
import com.autox.user.dto.UserSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one admin-only endpoint in this task. It exists so role-based authorisation is
 * real and testable rather than configured-but-unexercised; listing moderation
 * endpoints come with the listings work.
 *
 * <p>Guarded twice on purpose: by path in {@code SecurityConfig} and by
 * {@code @PreAuthorize} here, so moving the controller cannot silently unguard it.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AppUserRepository users;

    public AdminUserController(AppUserRepository users) {
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<UserSummary> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) UserRole role) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(
                role == null ? users.findAllActive(pageRequest) : users.findAllActiveByRole(role, pageRequest),
                UserSummary::from);
    }
}
