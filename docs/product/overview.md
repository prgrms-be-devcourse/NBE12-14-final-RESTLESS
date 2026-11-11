# 제품 개요

## 서비스

팬덤 이벤트 커머스 플랫폼. 팬이 한정판 굿즈를 구매하고, 콘서트 좌석을 예매하고, 선착순 쿠폰을 받고, 아티스트 관련 상품 경매에 참여한다.

기술적 핵심은 동시 요청이 몰리는 상황에서 거래 정합성을 지키고, 처리량, 지연시간, 안정성을 측정하며 개선하는 것이다.

## 핵심 도메인

| 도메인 | 주요 기능 | 문서 |
|---|---|---|
| Coupon | 선착순 쿠폰 발급 | [domains/coupon](../domains/coupon/) |
| Ticketing | 지정 좌석 콘서트 예매, PG 결제 | [domains/ticketing](../domains/ticketing/) |
| Drop | 한정판 굿즈 판매, 구매 대기열, 재고 예약, PG 결제 | [domains/drop](../domains/drop/) |
| Auction | 실시간 입찰, 경매 | [domains/auction](../domains/auction/) |

공통 모듈 후보: Auth/Member, Order, Payment, Checkout(Purchase Coordinator), Event, Statistics, Common Error/Security

## 목표

기능 목표: 네 도메인이 공통 인증, 주문, 결제로 연결되는 서비스 구현

엔지니어링 목표:

1. 고동시성 거래 정합성
2. 높은 지속 처리량
3. 낮은 p95/p99 응답 지연
4. Admission Control 기반 과부하 제어
5. 병목 분석과 재현 가능한 성능 개선
6. 확장성과 장애 복원력 검증

## 성과 기준

기술을 많이 썼다는 설명보다 아래 과정을 성과로 삼는다.

문제 재현 → 관측 → 가설 → 개선 → 정확성 검증 → 동일 조건 재측정 → 기술 선택 근거 기록
