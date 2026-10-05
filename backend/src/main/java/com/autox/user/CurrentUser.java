package com.autox.user;

import com.autox.api.error.ApiException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the authenticated user's id out of the access token's subject. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt token) {
        if (token == null) {
            throw ApiException.invalidToken("Authentication is required.");
        }
        try {
            return UUID.fromString(token.getSubject());
        } catch (IllegalArgumentException | NullPointerException malformed) {
            // A well-formed signature over a subject that is not a user id means the
            // token was minted by something that does not share our contract.
            throw ApiException.invalidToken("Access token subject is not a valid user id.");
        }
    }
}
