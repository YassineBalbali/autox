package com.autox.assistant;

import com.autox.user.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assistant AutoX (docs/assistant-plan.md). Both endpoints answer 404 unless
 * {@code AUTOX_ASSISTANT_ENABLED} is on and a planner is configured.
 *
 * <p>Public, as the search is: anonymous use is allowed (D3), with a lower daily limit than a
 * signed-in account's. A bearer token is read only for that limit.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant", description = "Assistant AutoX: a conversation answered from the site's own data. "
        + "404 when the assistant is off. Anonymous use allowed, with a lower limit.")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    /**
     * Whether the assistant can answer now. 404 when it is off; {@code available} false with reason
     * {@code BUSY} when today's budget is spent. Asked when the panel opens, not with the page, so a
     * visitor who never opens it wakes nothing.
     */
    @io.swagger.v3.oas.annotations.Operation(operationId = "assistantCapabilities")
    @GetMapping("/capabilities")
    public AssistantCapabilities capabilities() {
        return service.capabilities();
    }

    /**
     * One turn: the user's message, and the state the previous answer returned. The answer is a
     * list of blocks built by the server from its own tools, and the state to send next.
     *
     * <p>404 when the assistant is off. 400 for a message over 500 characters or a malformed state.
     * 429 {@code RATE_LIMITED} past the per-address or daily limit, or when the server is pacing its
     * turns. 503 {@code ASSISTANT_UNAVAILABLE} when the planner fails, times out or the day's budget
     * is spent: then use the ordinary search. Not retried by the client: a retry spends quota.
     */
    @io.swagger.v3.oas.annotations.Operation(operationId = "assistantTurn")
    @PostMapping("/turns")
    public TurnResponse turn(@Valid @RequestBody TurnRequest request, @AuthenticationPrincipal Jwt token,
                             HttpServletRequest http, HttpServletResponse response) {
        UUID account = token == null ? null : CurrentUser.id(token);
        return service.turn(request, http.getRemoteAddr(), account,
                trace -> response.setHeader(EvalTrace.HEADER, trace));
    }
}
