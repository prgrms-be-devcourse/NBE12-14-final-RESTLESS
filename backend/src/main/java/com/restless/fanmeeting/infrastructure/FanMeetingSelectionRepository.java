package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingSelection;
import org.springframework.data.jpa.repository.JpaRepository;

/** 선발 Repository. */
public interface FanMeetingSelectionRepository extends JpaRepository<FanMeetingSelection, Long> {
}
