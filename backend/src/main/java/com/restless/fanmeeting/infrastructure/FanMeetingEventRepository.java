package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import com.restless.fanmeeting.domain.FanMeetingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 행사 Repository. */
public interface FanMeetingEventRepository extends JpaRepository<FanMeetingEvent, Long> {

    /** 행사를 공유 잠금(FOR SHARE)으로 읽는다. 응모·취소처럼 행사를 고치지 않고 붙잡기만 할 때. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from FanMeetingEvent e where e.id = :id")
    Optional<FanMeetingEvent> findByIdForShare(@Param("id") Long id);

    /** 행사를 배타 잠금(FOR UPDATE)으로 읽는다. 열기·마감처럼 행사를 고칠 때. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from FanMeetingEvent e where e.id = :id")
    Optional<FanMeetingEvent> findByIdForUpdate(@Param("id") Long id);

    /** 열 행사: 오픈 시각이 지났고 마감 시각은 아직. 놓친 행사는 따로 찾아 자리를 차지하지 않게 한다. */
    @Query("""
            select e.id from FanMeetingEvent e
             where e.status = :status and e.entryOpensAt <= :now and e.entryClosesAt > :now
             order by e.entryOpensAt
            """)
    List<Long> findIdsToOpen(@Param("status") FanMeetingStatus status, @Param("now") Instant now, Pageable limit);

    /** 열지 못한 채 마감 시각까지 지난 행사(FANMEETING_OPEN_MISSED). */
    @Query("""
            select e.id from FanMeetingEvent e
             where e.status = :status and e.entryClosesAt <= :now
             order by e.entryClosesAt
            """)
    List<Long> findIdsOpenMissed(@Param("status") FanMeetingStatus status, @Param("now") Instant now, Pageable limit);

    /** 닫을 행사: OPEN이고 마감 시각이 지났다. */
    @Query("""
            select e.id from FanMeetingEvent e
             where e.status = :status and e.entryClosesAt <= :now
             order by e.entryClosesAt
            """)
    List<Long> findIdsToClose(@Param("status") FanMeetingStatus status, @Param("now") Instant now, Pageable limit);
}
