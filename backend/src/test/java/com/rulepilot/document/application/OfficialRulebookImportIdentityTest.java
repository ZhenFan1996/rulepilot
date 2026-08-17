package com.rulepilot.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialRulebookImportIdentityTest {

    @Test
    void acceptsAWellFormedKnownBcp47TagWithVariantsAndExtensions() {
        var source = new OfficialRulebookImportIdentity.SourceClaim(
                UUID.randomUUID(),
                "First edition",
                "de-Latn-DE-1996-u-co-phonebk",
                true);

        assertThat(source.language()).isEqualTo("de-Latn-DE-1996-u-co-phonebk");
    }

    @Test
    void stillRejectsAHumanLanguageNameThatIsNotAKnownLanguageSubtag() {
        assertThatThrownBy(() -> new OfficialRulebookImportIdentity.SourceClaim(
                        UUID.randomUUID(), "First edition", "English", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("language tag");
    }
}
