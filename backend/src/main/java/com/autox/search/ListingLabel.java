package com.autox.search;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * What a listing is called outside the site: "Audi A3 35 TDI". The result cards' heading on one
 * line, make and model then the version, and like them it falls back on the seller's title when
 * there is no make. Never make plus title: a seller's title usually starts with the make already.
 */
public final class ListingLabel {

    private ListingLabel() {
    }

    public static String of(String make, String model, String version, String title) {
        if (isBlank(make)) {
            return title == null ? "" : title.strip();
        }
        StringJoiner label = new StringJoiner(" ");
        label.add(make.strip());
        if (!isBlank(model)) {
            label.add(withoutLeadingMake(model.strip(), make.strip()));
        }
        if (!isBlank(version)) {
            label.add(version.strip());
        }
        return label.toString();
    }

    /** A model typed as "Audi A3", under the make "Audi", is the A3. */
    private static String withoutLeadingMake(String model, String make) {
        String prefix = make.toLowerCase(Locale.ROOT) + " ";
        return model.toLowerCase(Locale.ROOT).startsWith(prefix) ? model.substring(prefix.length()).strip() : model;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
