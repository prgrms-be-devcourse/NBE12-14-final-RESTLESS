package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingEvent;
import jakarta.persistence.LockModeType;
import java.util.Optional;
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
}
