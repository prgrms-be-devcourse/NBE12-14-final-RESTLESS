package com.restless.fanmeeting.infrastructure;

import com.restless.fanmeeting.domain.FanMeetingDrawRun;
import org.springframework.data.jpa.repository.JpaRepository;

/** 추첨 실행 Repository. */
public interface FanMeetingDrawRunRepository extends JpaRepository<FanMeetingDrawRun, Long> {
}
