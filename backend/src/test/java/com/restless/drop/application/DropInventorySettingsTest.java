package com.restless.drop.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DropInventorySettingsTest {
    @Test
    void bucketCountOutsideOneToSixteenFailsStartup() {
        assertThat(new DropInventorySettings(1).bucketCount()).isEqualTo(1);
        assertThat(new DropInventorySettings(16).bucketCount()).isEqualTo(16);
        assertThatIllegalArgumentException().isThrownBy(() -> new DropInventorySettings(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new DropInventorySettings(17));
    }
}
