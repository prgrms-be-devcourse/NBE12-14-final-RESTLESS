package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingDrawRun;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 추첨 실행 Repository. */
public interface FanMeetingDrawRunRepository extends JpaRepository<FanMeetingDrawRun, Long> {

    /** 가져갈 추첨: PENDING이거나 lease가 끝난 RUNNING. 잠금 없는 조회이고, Claim에서 잠근 뒤 다시 확인한다. */
    @Query("""
            select r.id from FanMeetingDrawRun r
             where r.status = com.restless.fanmeeting.domain.FanMeetingDrawRunStatus.PENDING
                or (r.status = com.restless.fanmeeting.domain.FanMeetingDrawRunStatus.RUNNING and r.leaseUntil < :now)
             order by r.id
            """)
    List<Long> findIdsToDraw(@Param("now") Instant now, Pageable limit);

    /** 잠그기 전에 행사 ID만 읽는다(event_id는 바뀌지 않음). 엔티티를 미리 올리면 뒤의 잠금 조회가 옛 값을 볼 수 있다. */
    @Query("select r.eventId from FanMeetingDrawRun r where r.id = :id")
    Optional<Long> findEventIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from FanMeetingDrawRun r where r.id = :id")
    Optional<FanMeetingDrawRun> findByIdForUpdate(@Param("id") Long id);
}
