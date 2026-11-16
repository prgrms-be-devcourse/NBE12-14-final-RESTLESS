package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingSelection;
import com.restless.fanmeeting.domain.FanMeetingSelectionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 선발 Repository. */
public interface FanMeetingSelectionRepository extends JpaRepository<FanMeetingSelection, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FanMeetingSelection s where s.id = :id")
    Optional<FanMeetingSelection> findByIdForUpdate(@Param("id") Long id);

    /** 내 선발(잠금 없음). 응모 하나는 최대 한 번 뽑히므로 회원·행사당 최대 1건이다. */
    Optional<FanMeetingSelection> findByEventIdAndUserId(Long eventId, Long userId);

    /** 상태별 인원. */
    interface StatusCount {
        FanMeetingSelectionStatus getStatus();

        long getCount();
    }

    /** 승격을 기다리는 예비 1명(선발 id, 회원). */
    interface WaitingReserve {
        Long getSelectionId();

        Long getUserId();
    }

    /** 행사의 선발 상태별 인원. 승격에서는 행사를 잠근 뒤에 부른다(transactions.md 10절). */
    @Query("""
            select s.status as status, count(s) as count from FanMeetingSelection s
             where s.eventId = :eventId group by s.status
            """)
    List<StatusCount> countByStatus(@Param("eventId") Long eventId);

    /** 승격을 기다리는 예비를 순번대로(잠금 없음). 승격 전에 탈퇴 여부를 미리 확인할 때 쓴다. */
    @Query("""
            select s.id as selectionId, s.userId as userId from FanMeetingSelection s
             where s.eventId = :eventId
               and s.selectionType = com.restless.fanmeeting.domain.FanMeetingSelectionType.RESERVE
               and s.status = com.restless.fanmeeting.domain.FanMeetingSelectionStatus.WAITING
             order by s.selectionRank
            """)
    List<WaitingReserve> findWaitingReserves(@Param("eventId") Long eventId, Pageable limit);

    /** 승격을 기다리는 예비를 순번대로 잠근다. 행사를 FOR UPDATE로 잠근 뒤에만 부른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from FanMeetingSelection s
             where s.eventId = :eventId
               and s.selectionType = com.restless.fanmeeting.domain.FanMeetingSelectionType.RESERVE
               and s.status = com.restless.fanmeeting.domain.FanMeetingSelectionStatus.WAITING
             order by s.selectionRank
            """)
    List<FanMeetingSelection> findWaitingReservesForUpdate(@Param("eventId") Long eventId, Pageable limit);

    /**
     * 승격된 예비 중 가장 뒤 순번. 승격된 예비는 기다리는 중(WAITING)도, 승격 없이 끝난 것(NOT_NEEDED)도 아닌 예비다.
     * 없으면 null.
     */
    @Query("""
            select max(s.selectionRank) from FanMeetingSelection s
             where s.eventId = :eventId
               and s.selectionType = com.restless.fanmeeting.domain.FanMeetingSelectionType.RESERVE
               and s.status not in (com.restless.fanmeeting.domain.FanMeetingSelectionStatus.WAITING,
                                    com.restless.fanmeeting.domain.FanMeetingSelectionStatus.NOT_NEEDED)
            """)
    Integer findMaxPromotedReserveRank(@Param("eventId") Long eventId);
}
