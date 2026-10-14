package com.autox.throttle;

import com.autox.api.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link PublicRateLimitService} to the endpoints it is registered against.
 *
 * <p>An interceptor rather than a security filter, so the refusal goes through the normal
 * {@code ApiExceptionHandler} and comes back as the same {@code problem+json} shape as
 * every other error, with {@code Retry-After} set.
 */
public class PublicRateLimitInterceptor implements HandlerInterceptor {

    private final PublicRateLimitService rateLimit;

    public PublicRateLimitInterceptor(PublicRateLimitService rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // The registered paths also carry authenticated writes — creating a listing POSTs to
        // /api/v1/listings. Those are bounded by needing an account, so only reads are counted.
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        PublicRateLimitService.Decision decision = rateLimit.record(clientIp(request));
        if (!decision.allowed()) {
            throw ApiException.rateLimited(decision.retryAfterSeconds());
        }
        return true;
    }

    /**
     * The real client address behind a proxy: with
     * {@code server.forward-headers-strategy=native}, Tomcat's RemoteIpValve has already
     * replaced the socket address with the client named by a header it trusts — read from
     * the right, and only from an internal proxy. See the README, "Deploying behind a proxy".
     */
    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? null : address;
    }
}
