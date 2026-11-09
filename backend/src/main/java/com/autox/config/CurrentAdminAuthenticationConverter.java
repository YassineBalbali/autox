package com.autox.config;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * Turns an access token into who is calling: the one place that decides whether the account behind
 * a token still exists, and whether it is still an administrator.
 *
 * <p>A token cannot be recalled, and lives 15 minutes. Two things cannot wait that long, so the
 * account's row is read on every authenticated request, one lookup by primary key:
 *
 * <ul>
 *   <li><b>A suspended or deleted account</b>, or one whose row is gone (a demo removal), is refused
 *       at once: the request is a 401, as for an expired token, and the site signs out. Its refresh
 *       tokens went with the suspension or the deletion, so there is no new token to get either.</li>
 *   <li><b>A token that claims ADMIN</b> keeps the claim only while the row backs it. Otherwise
 *       it could keep moderating and reading every address on the site after a demotion or a
 *       suspension. The claim is removed from the token before anything else sees it.</li>
 * </ul>
 *
 * <p>Here rather than in a filter on {@code /api/v1/admin/**}, because those paths are not the only
 * thing the claim opens: {@code CurrentUser.isAdmin} lets an administrator act on anyone's listing
 * through the sellers' own endpoints. Everything downstream reads the corrected token, so path
 * rules, {@code @PreAuthorize} and {@code isAdmin} cannot disagree, and a new endpoint is covered
 * without anyone remembering to.
 *
 * <p>Only rights are taken away here, never given. An account promoted a minute ago has a token
 * that does not say ADMIN, and gets the rights with its next one.
 */
@Component
class CurrentAdminAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcTemplate jdbc;
    private final JwtAuthenticationConverter delegate;

    CurrentAdminAuthenticationConverter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtClaims.ROLE);
        authorities.setAuthorityPrefix("ROLE_");
        delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(authorities);
    }

    /** What the row says now, for an account that still exists. */
    private record Account(boolean administrator) {
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        Account account = currentAccount(token.getSubject());
        if (account == null) {
            // An AuthenticationException here is the bearer filter's 401, with the usual body.
            throw new InvalidBearerTokenException("The account behind this token no longer exists");
        }
        boolean claimsAdmin = "ADMIN".equals(token.getClaimAsString(JwtClaims.ROLE));
        return delegate.convert(claimsAdmin && !account.administrator() ? withoutRole(token) : token);
    }

    /** Null when no live account has this id: a malformed subject, a row gone, an account suspended or deleted. */
    private Account currentAccount(String subject) {
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return null;
        }
        List<Account> rows = jdbc.query("""
                SELECT role = 'ADMIN' AS administrator
                  FROM app_user WHERE id = ? AND status NOT IN ('SUSPENDED', 'DELETED') AND deleted_at IS NULL""",
                (row, n) -> new Account(row.getBoolean("administrator")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Jwt withoutRole(Jwt token) {
        return Jwt.withTokenValue(token.getTokenValue())
                .headers(headers -> headers.putAll(token.getHeaders()))
                .claims(claims -> {
                    claims.putAll(token.getClaims());
                    claims.remove(JwtClaims.ROLE);
                })
                .build();
    }
}
