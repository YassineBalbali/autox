package com.autox.savedsearch;

import com.autox.search.ListingSearchCriteria;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A search's filters as they are stored: {@code ListingSearchCriteria} as JSON, with a version
 * beside it.
 *
 * <p><b>What a later change to the filters has to do.</b> Adding one needs nothing: an old search
 * simply does not have it. A field that goes is ignored when read. A value of a filter that goes,
 * a fuel type say, is dropped, and the rest of the filter stands. Renaming a field, or changing
 * what it means, is what the version is for: raise {@link #CURRENT_VERSION}, and add the step from
 * the old shape to {@link #upgrade}.
 *
 * <p>A search that still cannot be read is left out and reported on its page, not a failure of the
 * job that every other search depends on.
 */
@Component
public class SavedSearchCriteriaCodec {

    public static final int CURRENT_VERSION = 1;

    private final ObjectMapper json;

    public SavedSearchCriteriaCodec(ObjectMapper applicationMapper) {
        this.json = applicationMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        // A value that no longer exists reads as null, and a list of filters does not hold one.
        this.json.configOverride(List.class).setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP));
    }

    public String write(ListingSearchCriteria criteria) {
        try {
            return json.writeValueAsString(criteria);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Search criteria could not be written", impossible);
        }
    }

    /** The stored form, in the current version's shape, or empty if it cannot be understood. */
    public Optional<ObjectNode> readTree(int version, String stored) {
        if (version < 1 || version > CURRENT_VERSION) {
            return Optional.empty();
        }
        try {
            return json.readTree(stored) instanceof ObjectNode node ? Optional.of(upgrade(version, node)) : Optional.empty();
        } catch (JsonProcessingException unreadable) {
            return Optional.empty();
        }
    }

    /** The filters, or empty if the stored values do not fit them. */
    public Optional<ListingSearchCriteria> bind(ObjectNode tree) {
        try {
            return Optional.of(json.treeToValue(tree, ListingSearchCriteria.class));
        } catch (JsonProcessingException wrongShape) {
            return Optional.empty();
        }
    }

    /** One step per version from {@code version} up to the current one. There are none yet. */
    private static ObjectNode upgrade(int version, ObjectNode stored) {
        return stored;
    }
}
