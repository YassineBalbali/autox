package com.autox.storage;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scheduled storage check: it touches storage every time it runs, and an outage is
 * logged rather than thrown into the scheduler.
 */
class StorageReadinessCheckTest {

    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final StorageReadinessCheck check = new StorageReadinessCheck(storage);

    @Test
    @DisplayName("each run reaches storage — which is what keeps a free Supabase project awake")
    void eachRunReachesStorage() {
        check.recheck();
        check.recheck();

        verify(storage, times(2)).ensureReady();
    }

    @Test
    @DisplayName("an outage is reported, not thrown: the next run still happens")
    void outageIsLoggedNotThrown() {
        doThrow(new IllegalStateException("Storage bucket 'autox-photos' is not reachable."))
                .when(storage).ensureReady();

        assertThatNoException().isThrownBy(check::recheck);
    }
}
