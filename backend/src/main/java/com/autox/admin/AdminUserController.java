package com.autox.admin;

import com.autox.admin.AdminUsers.AdminUserActionResult;
import com.autox.admin.AdminUsers.AdminUserDetail;
import com.autox.admin.AdminUsers.AdminUserRow;
import com.autox.admin.AdminUsers.ChangeUserRoleRequest;
import com.autox.admin.AdminUsers.HideDealerPageRequest;
import com.autox.admin.AdminUsers.ReactivateUserRequest;
import com.autox.admin.AdminUsers.ShowDealerPageRequest;
import com.autox.admin.AdminUsers.SuspendUserRequest;
import com.autox.api.PageResponse;
import com.autox.user.CurrentUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts, for administrators: find one, read it, suspend it, reactivate it, change its role.
 * The rules are in {@link AdminUserService}.
 *
 * <p>Guarded twice on purpose: by path in {@code SecurityConfig} and by
 * {@code @PreAuthorize} here, so moving the controller cannot silently unguard it.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin", description = "Moderation. Requires the ADMIN role.")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/users")
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    /**
     * Accounts, newest first. Paginated by page number rather than by cursor: this is an
     * operator tool over a slowly growing table, where jumping to a page is worth more than the
     * consistency a cursor gives.
     *
     * <p>Closed accounts are excluded. Password hashes are never included.
     *
     * @param q    part of a display name or an address, whatever its case
     * @param demo true for the demo seed's accounts only, false for everyone else's
     */
    @GetMapping
    public PageResponse<AdminUserRow> userList(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) @Size(max = 255) String q,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean demo) {

        return PageResponse.of(
                service.list(new AdminUserRepository.Filter(q, role, status, demo),
                        PageRequest.of(page, size)),
                Function.identity());
    }

    @GetMapping("/{id}")
    public AdminUserDetail userDetail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/suspend")
    public AdminUserActionResult suspendUser(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                         @Valid @RequestBody SuspendUserRequest request) {
        return service.suspend(CurrentUser.id(token), id, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    public AdminUserActionResult reactivateUser(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                            @Valid @RequestBody(required = false) ReactivateUserRequest request) {
        return service.reactivate(CurrentUser.id(token), id, request == null ? null : request.note());
    }

    /** Takes a dealer's public page off the site: a 404 to the public, the dealer and their listings untouched. */
    @PostMapping("/{id}/dealer-page/hide")
    public AdminUserActionResult hideDealerPage(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                                @Valid @RequestBody HideDealerPageRequest request) {
        return service.hideDealerPage(CurrentUser.id(token), id, request.reason());
    }

    @PostMapping("/{id}/dealer-page/show")
    public AdminUserActionResult showDealerPage(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                                @Valid @RequestBody(required = false) ShowDealerPageRequest request) {
        return service.showDealerPage(CurrentUser.id(token), id, request == null ? null : request.note());
    }

    @PostMapping("/{id}/role")
    public AdminUserActionResult changeUserRole(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
                                            @Valid @RequestBody ChangeUserRoleRequest request) {
        return service.changeRole(CurrentUser.id(token), id, request.role(), request.note());
    }
}
