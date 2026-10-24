package com.autox.config;

import com.autox.throttle.PublicRateLimitInterceptor;
import com.autox.throttle.PublicRateLimitService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring. Currently only the public rate limit, which is registered against paths
 * rather than applied globally: the authenticated endpoints are already bounded by
 * needing an account, and counting them would put a database write in front of every
 * seller action for no benefit.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PublicRateLimitService rateLimit;

    public WebConfig(PublicRateLimitService rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicRateLimitInterceptor(rateLimit))
                .addPathPatterns(
                        "/api/v1/listings",
                        "/api/v1/listings/*",
                        "/api/v1/listings/*/similar",
                        "/api/v1/valuation",
                        "/api/v1/valuation/engines",
                        "/api/v1/reference/**")
                // The seller's own views live under the same prefix and are not public.
                .excludePathPatterns(
                        "/api/v1/listings/mine",
                        "/api/v1/listings/mine/**");
    }

    /**
     * ETags for the reference endpoints only.
     *
     * <p>Scoped deliberately. The filter has to buffer the whole response in memory to hash it,
     * which is the right trade for a few kilobytes of vocabulary a client re-requests on every
     * load, and the wrong one for search results that differ per caller and are never re-fetched
     * identically.
     *
     * <p>It saves the body, not the query: a 304 still runs the lookup. That is acceptable here
     * because these are small indexed reads, and noting it is cheaper than pretending otherwise.
     */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> referenceEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/reference/*");
        registration.setName("referenceEtagFilter");
        return registration;
    }
}
