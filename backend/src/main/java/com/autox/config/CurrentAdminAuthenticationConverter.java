package com.autox.config;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

/**
 * Turns an access token into who is calling, and is the one place administrator rights are decided.
 *
 * <p>The role travels in the token, which is what keeps an ordinary request free of a database
 * read. But a token cannot be recalled, and one that says ADMIN could otherwise keep moderating
 * and reading every address on the site for up to 15 minutes after a demotion or a suspension. So
 * a token that claims ADMIN, and only such a token, is checked against the account's row on every
 * request. If the row no longer backs the claim, the claim is removed from the token before anything
 * else sees it.
 *
 * <p>Here rather than in a filter on {@code /api/v1/admin/**}, because those paths are not the only
 * thing the claim opens: {@code CurrentUser.isAdmin} lets an administrator act on anyone's listing
 * through the sellers' own endpoints. Everything downstream reads the corrected token, so path
 * rules, {@code @PreAuthorize} and {@code isAdmin} cannot disagree, and a new admin endpoint is
 * covered without anyone remembering to.
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

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        boolean claimsAdmin = "ADMIN".equals(token.getClaimAsString(JwtClaims.ROLE));
        return delegate.convert(claimsAdmin && !isAdministratorNow(token.getSubject()) ? withoutRole(token) : token);
    }

    private boolean isAdministratorNow(String subject) {
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return false;
        }
        List<Boolean> rows = jdbc.queryForList("""
                SELECT role = 'ADMIN' AND status NOT IN ('SUSPENDED', 'DELETED') AND deleted_at IS NULL
                  FROM app_user WHERE id = ?""", Boolean.class, id);
        return rows.size() == 1 && Boolean.TRUE.equals(rows.getFirst());
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
