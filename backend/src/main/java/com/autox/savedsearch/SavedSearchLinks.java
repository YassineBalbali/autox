package com.autox.savedsearch;

import com.autox.mail.MailProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The site's addresses an alert links to. Each has to match its Angular route. */
@Component
public class SavedSearchLinks {

    private final String site;

    public SavedSearchLinks(MailProperties mail) {
        this.site = mail.siteUrl();
    }

    /** The results page, for this search. */
    public String results(Map<String, String> params) {
        String query = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return site + "/recherche" + (query.isEmpty() ? "" : "?" + query);
    }

    public String listing(UUID id) {
        return site + "/annonces/" + id;
    }

    public String mySearches() {
        return site + "/mes-recherches";
    }

    public String unsubscribe(String token) {
        return site + "/alertes/desabonnement?token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
