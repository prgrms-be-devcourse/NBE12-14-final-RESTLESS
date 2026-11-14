package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingSelection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 선발 Repository. */
public interface FanMeetingSelectionRepository extends JpaRepository<FanMeetingSelection, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FanMeetingSelection s where s.id = :id")
    Optional<FanMeetingSelection> findByIdForUpdate(@Param("id") Long id);
}
