package com.autox.savedsearch;

import com.autox.search.ListingSearchCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A saved search as it can run today: its filters, less any make or model the catalogue no longer
 * has, and the query string back to the results page, less the same.
 */
public record ResolvedSearch(
        ListingSearchCriteria criteria,
        Map<String, String> linkParams,
        boolean readable,
        String missingMake,
        String missingModel) {

    private static final String UNKNOWN_MAKE = "marque";
    private static final String UNKNOWN_MODEL = "modele";

    @Component
    public static class Resolver {

        private static final TypeReference<LinkedHashMap<String, String>> PARAMS = new TypeReference<>() { };

        private final SavedSearchCriteriaCodec codec;
        private final SavedSearchRepository repository;
        private final ObjectMapper json;

        public Resolver(SavedSearchCriteriaCodec codec, SavedSearchRepository repository, ObjectMapper json) {
            this.codec = codec;
            this.repository = repository;
            this.json = json;
        }

        public ResolvedSearch resolve(SavedSearchRepository.Row row) {
            Map<String, String> params = linkParams(row.linkParams());
            ObjectNode tree = codec.readTree(row.criteriaVersion(), row.criteria()).orElse(null);
            if (tree == null) {
                return new ResolvedSearch(null, params, false, null, null);
            }

            String missingMake = null;
            String missingModel = null;
            UUID makeId = uuid(tree, "makeId");
            if (makeId != null && repository.makeName(makeId).isEmpty()) {
                missingMake = row.makeLabel() == null ? "" : row.makeLabel();
                // A model means nothing without its make, as on the results page.
                tree.remove(List.of("makeId", "make", "modelId", "model"));
                params.remove(UNKNOWN_MAKE);
                params.remove(UNKNOWN_MODEL);
            } else {
                UUID modelId = uuid(tree, "modelId");
                if (modelId != null && repository.modelName(modelId).isEmpty()) {
                    missingModel = row.modelLabel() == null ? "" : row.modelLabel();
                    tree.remove("modelId");
                    params.remove(UNKNOWN_MODEL);
                }
            }

            ListingSearchCriteria criteria = codec.bind(tree).orElse(null);
            return new ResolvedSearch(criteria, params, criteria != null, missingMake, missingModel);
        }

        private Map<String, String> linkParams(String stored) {
            try {
                return json.readValue(stored, PARAMS);
            } catch (JsonProcessingException unreadable) {
                return new LinkedHashMap<>();
            }
        }

        private static UUID uuid(ObjectNode tree, String field) {
            if (!tree.hasNonNull(field)) {
                return null;
            }
            try {
                return UUID.fromString(tree.get(field).asText());
            } catch (IllegalArgumentException notAnId) {
                return null;
            }
        }
    }
}
