package com.restless.concert.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공연의 공통 가격. 판매 좌석과 거래 당시 가격은 회차별로 별도 보존한다. */
@Entity
@Table(name = "concert_section_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConcertSectionPrice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "concert_id", nullable = false)
    private Long concertId;

    @Column(name = "section_label", nullable = false, length = 50)
    private String sectionLabel;

    @Column(nullable = false, length = 20)
    private String grade;

    @Column(nullable = false)
    private long price;

    public static ConcertSectionPrice create(Long concertId, String sectionLabel, String grade, long price) {
        if (concertId == null || concertId <= 0 || sectionLabel == null || sectionLabel.isBlank()
                || sectionLabel.length() > 50 || grade == null || grade.isBlank() || grade.length() > 20 || price < 1) {
            throw new IllegalArgumentException("공연 구역별 등급·가격이 올바르지 않습니다.");
        }
        ConcertSectionPrice result = new ConcertSectionPrice();
        result.concertId = concertId;
        result.sectionLabel = sectionLabel;
        result.grade = grade;
        result.price = price;
        return result;
    }
}
