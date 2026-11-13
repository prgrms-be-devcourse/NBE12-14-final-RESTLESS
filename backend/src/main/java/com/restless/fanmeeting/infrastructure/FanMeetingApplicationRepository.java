package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingApplication;
import com.restless.fanmeeting.domain.FanMeetingApplicationStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 응모 Repository. */
public interface FanMeetingApplicationRepository extends JpaRepository<FanMeetingApplication, Long> {

    /** 내 응모 조회(잠금 없음). */
    Optional<FanMeetingApplication> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * 응모·취소 전 잠금 없는 조회. id와 상태만 읽어 엔티티를 영속성 컨텍스트에 올리지 않는다.
     * 그래야 뒤의 FOR UPDATE 조회가 최신 상태를 읽는다. 없는 행을 FOR UPDATE로 찾지 않는다(transactions.md 1절 8번).
     */
    Optional<ApplicationState> findStateByEventIdAndUserId(Long eventId, Long userId);

    /** 있는 응모 행을 잠그고 다시 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FanMeetingApplication a where a.id = :id")
    Optional<FanMeetingApplication> findByIdForUpdate(@Param("id") Long id);

    /** 추첨 후보: 유효한(APPLIED) 응모를 응모 ID 순서로. 잠그지 않는다(추첨 계산은 트랜잭션 밖). */
    @Query("""
            select a.id as applicationId, a.userId as userId from FanMeetingApplication a
             where a.eventId = :eventId
               and a.status = com.restless.fanmeeting.domain.FanMeetingApplicationStatus.APPLIED
             order by a.id
            """)
    List<Candidate> findCandidates(@Param("eventId") Long eventId);

    interface ApplicationState {
        Long getId();

        FanMeetingApplicationStatus getStatus();
    }

    interface Candidate {
        Long getApplicationId();

        Long getUserId();
    }
}
