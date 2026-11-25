package com.restless.concert.application.dto;

/** 배치의 판매 가능 좌석 표현. 회차 좌석 생성과 준비 확인이 공유한다. */
public final class SeatSetupLayout {
    private SeatSetupLayout() { }

    public record Seat(Long venueSeatId, String sectionLabel) { }
}
