package com.restless.drop.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "drop_purchase_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropPurchaseSlot {
    @EmbeddedId
    private Id id;
    @Column(name = "active_reservation_id")
    private Long activeReservationId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void occupy(Long reservationId, Instant now) {
        if (activeReservationId != null) throw new IllegalStateException("Purchase slot is occupied");
        activeReservationId = Objects.requireNonNull(reservationId);
        updatedAt = now;
    }

    @Embeddable
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Id implements Serializable {
        @Column(name = "drop_id", nullable = false)
        private Long dropId;
        @Column(name = "user_id", nullable = false)
        private Long userId;

        public Id(Long dropId, Long userId) {
            this.dropId = dropId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Id that && Objects.equals(dropId, that.dropId)
                    && Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dropId, userId);
        }
    }
}
