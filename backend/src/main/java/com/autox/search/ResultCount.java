package com.autox.search;

/**
 * How many listings match a search.
 *
 * @param count  the number of matches, never more than the cap
 * @param capped {@code true} when there are more matches than {@code count} — the client
 *               shows "more than {@code count}" rather than the figure itself
 */
public record ResultCount(int count, boolean capped) {
}
