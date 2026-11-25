package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.OrganizerConcertService;
import com.restless.member.application.MemberService;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.domain.Drop;
import com.restless.drop.domain.DropVariant;
import com.restless.drop.infrastructure.*;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DropAdminServiceTest {
    @Test
    void registrationRejectsCloseAtOrBeforeInjectedClockWithoutWriting() {
        var drops = mock(DropRepository.class);
        var variants = mock(DropVariantRepository.class);
        var inventories = mock(DropInventoryRepository.class);
        var concerts = mock(ConcertQueryService.class);
        var organizerConcerts = mock(OrganizerConcertService.class);
        var now = Instant.parse("2040-01-01T00:00:00Z");
        var service = new DropAdminService(drops, variants, inventories, concerts, organizerConcerts, mock(MemberService.class), mock(DropQueueGateRepository.class), new DropInventorySettings(4), Clock.fixed(now, ZoneOffset.UTC));
        for (Instant close : new Instant[] {now.minusNanos(1), now}) {
            var request = new CreateDropRequest(1L, "상품", "설명", "url", now.minusSeconds(60), close,
                    List.of(new CreateDropRequest.Variant("A", "M", 1L, 1)));
            assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        verifyNoInteractions(drops, variants, inventories, concerts, organizerConcerts);
    }

    @Test
    void databaseCollationDuplicateIsAlsoInvalidRequest() {
        var drops = mock(DropRepository.class);
        var variants = mock(DropVariantRepository.class);
        var inventories = mock(DropInventoryRepository.class);
        var concerts = mock(ConcertQueryService.class);
        var organizerConcerts = mock(OrganizerConcertService.class);
        var now = Instant.EPOCH;
        var service = new DropAdminService(drops, variants, inventories, concerts, organizerConcerts, mock(MemberService.class), mock(DropQueueGateRepository.class), new DropInventorySettings(4), Clock.fixed(now, ZoneOffset.UTC));
        when(drops.save(any(Drop.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var sqlFailure = new SQLException("Duplicate entry", "23000", 1062);
        when(variants.save(any(DropVariant.class))).thenThrow(new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", sqlFailure, "drop_variants.uk_drop_variants_sku")));
        var request = new CreateDropRequest(1L, "상품", "설명", "url", now, now.plusSeconds(60),
                List.of(new CreateDropRequest.Variant("ss", "M", 1000L, 1)));
        assertThatThrownBy(() -> service.create(request)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verifyNoInteractions(inventories);
    }

    @Test
    void organizerOpenHourAcceptsOnlyWholeHoursFrom8To22Kst() {
        var kst = java.time.ZoneId.of("Asia/Seoul");
        var day = java.time.LocalDate.of(2040, 1, 1);
        for (var ok : List.of(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(15, 0), java.time.LocalTime.of(22, 0))) {
            assertThatCode(() -> DropAdminService.requireOrganizerOpenHour(day.atTime(ok).atZone(kst).toInstant()))
                    .doesNotThrowAnyException();
        }
        for (var bad : List.of(java.time.LocalTime.of(7, 0), java.time.LocalTime.of(23, 0), java.time.LocalTime.of(0, 0),
                java.time.LocalTime.of(21, 59), java.time.LocalTime.of(22, 0, 1), java.time.LocalTime.of(8, 0, 0, 1000))) {
            assertThatThrownBy(() -> DropAdminService.requireOrganizerOpenHour(day.atTime(bad).atZone(kst).toInstant()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }
}
