package com.restless.citmp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Temporary: fails on purpose to check that CI uploads test reports (#122). Reverted in the same PR. */
class CiArtifactProbeTest {
    @Test
    void failsOnPurpose() {
        assertThat("actual-value").isEqualTo("expected-value");
    }
}
