package com.rulepilot.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Availability;
import com.rulepilot.catalog.PublicTeachingContinuationCatalog.Continuation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicTeachingContinuationCatalogTest {

    @Test
    void rejectsANullContinuationKeyWithTheContractException() {
        Map<Integer, Continuation> continuations = new LinkedHashMap<>();
        continuations.put(null, continuation(123));

        assertThatThrownBy(() -> Availability.available(continuations))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("public teaching availability identity is invalid");
    }

    @Test
    void rejectsANullContinuationValueWithTheContractException() {
        Map<Integer, Continuation> continuations = new LinkedHashMap<>();
        continuations.put(123, null);

        assertThatThrownBy(() -> Availability.available(continuations))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("public teaching availability identity is invalid");
    }

    private Continuation continuation(int bggId) {
        return new Continuation(bggId, UUID.randomUUID(), 3, 8);
    }
}
