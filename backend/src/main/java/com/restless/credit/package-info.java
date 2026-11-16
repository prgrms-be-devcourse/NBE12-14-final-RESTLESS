/**
 * 크레딧 지갑: 지급, 확보, 해제, 차감, 변동 내역
 *
 * <p>1차에서는 경매만 쓴다. 확보·해제·차감은 경매의 입찰·종료 트랜잭션 안에서
 * {@code application.CreditWalletService}로만 부른다 (docs/domains/auction/transactions.md).
 *
 * <p>담당: 천종원
 *
 * <p>하위 패키지: api(Controller, DTO), application(유스케이스, 트랜잭션 경계),
 * domain(엔티티, 정책), infrastructure(Repository, 외부 연동).
 * 다른 도메인의 infrastructure 패키지를 직접 참조하지 않는다.
 */
package com.restless.credit;
