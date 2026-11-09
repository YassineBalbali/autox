package com.autox.dealer;

import com.autox.messaging.dto.ConversationDetail;
import com.autox.messaging.dto.SendMessageRequest;
import com.autox.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * The directory of professionals, their pages, and writing to one from their page.
 *
 * <p>A dealer's listings are not here: they are the public search with {@code sellerId} set, so
 * the page has every filter and sort the search has, and gains each one the search gains.
 */
@RestController
@RequestMapping("/api/v1/dealers")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Dealers", description = "The directory of professionals and their public pages. Reading needs no authentication; writing to a dealer does.")
@Validated
public class DealerController {

    private final DealerService service;

    public DealerController(DealerService service) {
        this.service = service;
    }

    /**
     * Professionals with at least one listing online.
     *
     * @param q           part of the name, accents and case aside
     * @param departement "33", "974", "2A"
     * @param city        part of the town's name, or the start of its postal code
     */
    @GetMapping
    public DealerDirectory dealerDirectory(
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Pattern(regexp = "^(\\d{2,3}|2[AaBb])$") String departement,
            @RequestParam(required = false) @Size(max = 80) String city,
            @RequestParam(defaultValue = "LISTINGS") DealerSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(DealerService.MAX_PAGE_SIZE) int size) {
        return service.directory(new DealerRepository.Filter(q, departement, city), sort, page, size);
    }

    /** A dealer's page: 404 when the public may not see it, except to the dealer and administrators. */
    @GetMapping("/{slug}")
    public DealerPage dealerPage(@AuthenticationPrincipal Jwt token,
                             @PathVariable @Pattern(regexp = "^[a-z0-9-]{1,72}$") String slug) {
        return service.page(slug, token == null ? null : CurrentUser.id(token), token != null && CurrentUser.isAdmin(token));
    }

    /** Writes to the dealer through AutoX's messaging: starts the conversation, or continues it. */
    @PostMapping("/{slug}/conversation")
    public ConversationDetail contactDealer(@AuthenticationPrincipal Jwt token,
                                      @PathVariable @Pattern(regexp = "^[a-z0-9-]{1,72}$") String slug,
                                      @Valid @RequestBody SendMessageRequest request) {
        return service.contact(slug, CurrentUser.id(token), request.body());
    }
}
