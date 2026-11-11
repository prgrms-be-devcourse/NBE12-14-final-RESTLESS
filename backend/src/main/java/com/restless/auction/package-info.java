/**
 * 경매, 입찰, 크레딧
 *
 * <p>크레딧을 공통 모듈로 뺄지는 미정이다 (docs/architecture/erd.md 7절).
 *
 * <p>담당: 천종원
 *
 * <p>하위 패키지: api(Controller, DTO), application(유스케이스, 트랜잭션 경계),
 * domain(엔티티, 정책), infrastructure(Repository, 외부 연동).
 * 다른 도메인의 infrastructure 패키지를 직접 참조하지 않는다.
 */
package com.restless.auction;
