/**
 * 경매, 입찰
 *
 * <p>크레딧 확보·해제·차감은 {@code com.restless.credit}의 CreditWalletService를 쓴다.
 * 크레딧 Repository를 직접 쓰지 않는다.
 *
 * <p>담당: 천종원
 *
 * <p>하위 패키지: api(Controller, DTO), application(유스케이스, 트랜잭션 경계),
 * domain(엔티티, 정책), infrastructure(Repository, 외부 연동).
 * 다른 도메인의 infrastructure 패키지를 직접 참조하지 않는다.
 */
package com.restless.auction;
