package com.autox.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Drops one line from the log: Apache HttpClient's "Invalid cookie header".
 *
 * <p>Cloudflare, in front of Supabase Storage, sets a {@code __cf_bm} cookie on every response whose
 * date the client's default cookie rules cannot read, and the client warns each time: dozens of
 * lines per photo upload. Nothing here uses cookies, so the warning says nothing.
 *
 * <p>Filtered, because the client cannot be told to ignore cookies: the SDK's
 * {@code ApacheHttpClient} builds its {@code HttpClient} itself and exposes no such setting.
 * Replacing that client would be a larger change than the problem, on a path checked against
 * Supabase's storage server. Only this message goes: the logger's level is left alone, so anything
 * else it has to say, "Cookie rejected" included, still appears.
 */
final class InvalidCookieWarningFilter extends TurboFilter {

    static final String LOGGER = "org.apache.http.client.protocol.ResponseProcessCookies";
    private static final String MESSAGE = "Invalid cookie header";

    /** Idempotent: every application context in a test run builds the client again. */
    static void install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context
                && context.getTurboFilterList().stream().noneMatch(InvalidCookieWarningFilter.class::isInstance)) {
            InvalidCookieWarningFilter filter = new InvalidCookieWarningFilter();
            filter.setContext(context);
            filter.start();
            context.addTurboFilter(filter);
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        return LOGGER.equals(logger.getName()) && format != null && format.startsWith(MESSAGE)
                ? FilterReply.DENY
                : FilterReply.NEUTRAL;
    }
}
