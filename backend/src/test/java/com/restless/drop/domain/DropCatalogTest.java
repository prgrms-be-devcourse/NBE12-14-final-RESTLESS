package com.restless.drop.domain;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DropCatalogTest {
    private final Instant opens = Instant.parse("2026-11-20T03:00:00Z");
    private final Instant closes = opens.plusSeconds(3600);

    private Drop draft() {
        return Drop.draft(1L, "상품", "설명", "https://example.com/a.png", opens, closes, opens.minusSeconds(1));
    }

    @Test
    void salePeriodIsStartInclusiveAndEndExclusive() {
        Drop drop = draft();
        drop.publish();
        assertThat(drop.saleStatus(opens.minusNanos(1))).isEqualTo("UPCOMING");
        assertThat(drop.saleStatus(opens)).isEqualTo("ON_SALE");
        assertThat(drop.saleStatus(closes.minusNanos(1))).isEqualTo("ON_SALE");
        assertThat(drop.saleStatus(closes)).isEqualTo("ENDED");
    }

    @Test
    void inv14EveryStatusUsesTheSameSaleDecision() {
        for (DropStatus status : DropStatus.values()) {
            Drop drop = draft();
            org.springframework.test.util.ReflectionTestUtils.setField(drop, "status", status);
            boolean enabled = status == DropStatus.SCHEDULED || status == DropStatus.OPEN;
            assertThat(drop.saleStatus(opens.minusNanos(1))).isEqualTo(enabled ? "UPCOMING" : "ENDED");
            assertThat(drop.saleStatus(opens)).isEqualTo(enabled ? "ON_SALE" : "ENDED");
            assertThat(drop.isOnSale(opens)).isEqualTo(enabled);
            assertThat(drop.isOnSale(opens.minusNanos(1))).isFalse();
            assertThat(drop.isOnSale(closes)).isFalse();
            assertThat(drop.saleStatus(closes)).isEqualTo("ENDED");
        }
    }

    @Test
    void registrationRequiresFutureCloseButAllowsPastStart() {
        for (Instant close : new Instant[] {opens.minusNanos(1), opens}) {
            assertThatThrownBy(() -> Drop.draft(1L, "상품", "설명", "url",
                    opens.minusSeconds(60), close, opens))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        assertThat(Drop.draft(1L, "상품", "설명", "url", opens.minusSeconds(60),
                opens.plusNanos(1), opens).getStatus()).isEqualTo(DropStatus.DRAFT);
    }

    @Test
    void onlyDraftCanBePublished() {
        Drop drop = draft();
        assertThat(drop.getStatus().isPublished()).isFalse();
        drop.publish();
        assertThat(drop.getStatus()).isEqualTo(DropStatus.SCHEDULED);
        assertThat(drop.getStatus().isPublished()).isTrue();
        assertThatThrownBy(drop::publish).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DROP_INVALID_STATE));
        assertThat(DropStatus.CANCELLED.isPublished()).isFalse();
        assertThat(DropStatus.OPEN.isPublished()).isTrue();
        assertThat(DropStatus.CLOSED.isPublished()).isTrue();
    }

    @Test
    void equalPeriodEndpointsAreInvalid() {
        assertThatThrownBy(() -> Drop.draft(1L, "상품", "설명", "url", opens, opens, opens))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void inv01AndInv02InitialInventoryConservesQuantity() {
        var inventory = DropInventory.initial(10L, 100);
        assertThat(inventory.getVariantId()).isEqualTo(10L);
        assertThat(inventory.getTotalQuantity()).isEqualTo(inventory.getAvailableQuantity()
                + inventory.getReservedQuantity() + inventory.getSoldQuantity());
        assertThat(inventory.getAvailableQuantity()).isEqualTo(100);
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getSoldQuantity()).isZero();
        assertThat(inventory.isSoldOut()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> DropInventory.initial(10L, 0));
    }
}
