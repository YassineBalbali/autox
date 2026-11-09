package com.autox.dealer;

import java.util.List;

/**
 * A page of the directory, and the départements it can be narrowed to.
 *
 * @param departements every département with a dealer in the directory, whatever the filters, with
 *                     how many: the choices of the département filter
 */
public record DealerDirectory(
        List<DealerCard> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<DepartementCount> departements) {

    public record DepartementCount(String code, int dealers) {
    }
}
