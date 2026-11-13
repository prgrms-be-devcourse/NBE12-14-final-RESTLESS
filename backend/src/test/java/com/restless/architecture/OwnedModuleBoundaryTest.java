package com.restless.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 결제·주문·드롭 모듈 경계 (AGENTS.md 5절, ADR-0003). <b>하네스 파일이다.</b>
 * 다른 담당자의 패키지는 검사하지 않는다. 규칙을 바꾸려면 하네스 PR로 바꾼다.
 */
@AnalyzeClasses(packages = "com.restless", importOptions = ImportOption.DoNotIncludeTests.class)
class OwnedModuleBoundaryTest {

    private static final String[] DOMAINS = {
            "com.restless.drop..", "com.restless.ticketing..", "com.restless.fanmeeting..",
            "com.restless.auction..", "com.restless.concert..", "com.restless.credit..", "com.restless.coupon.."};

    @ArchTest
    static final ArchRule paymentDoesNotKnowDomains = noClasses()
            .that().resideInAPackage("com.restless.payment..")
            .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
            .because("결제 모듈은 도메인을 import하지 않고 PaymentResultHandlerRegistry로만 찾는다 (ADR-0003)");

    @ArchTest
    static final ArchRule orderDoesNotKnowDomains = noClasses()
            .that().resideInAPackage("com.restless.order..")
            .should().dependOnClassesThat().resideInAnyPackage(DOMAINS)
            .because("공통 주문은 도메인을 import하지 않는다. 도메인이 OrderService를 부른다");

    @ArchTest
    static final ArchRule paymentAndOrderStayIndependent = noClasses()
            .that().resideInAPackage("com.restless.payment..")
            .should().dependOnClassesThat().resideInAPackage("com.restless.order..")
            .because("결제 대상은 (payableType, payableId)로 식별한다. 결제가 주문을 직접 알 필요가 없다 (ADR-0003)");

    @ArchTest
    static final ArchRule orderDoesNotKnowPayment = noClasses()
            .that().resideInAPackage("com.restless.order..")
            .should().dependOnClassesThat().resideInAPackage("com.restless.payment..")
            .because("주문 상태 변경은 도메인 Handler가 OrderService로 한다");

    @ArchTest
    static final ArchRule dropUsesNoForeignRepositories = noClasses()
            .that().resideInAPackage("com.restless.drop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.restless.order.infrastructure..", "com.restless.payment.infrastructure..",
                    "com.restless.member.infrastructure..", "com.restless.concert.infrastructure..",
                    "com.restless.ticketing..", "com.restless.fanmeeting..", "com.restless.auction..")
            .because("다른 도메인의 Repository를 직접 쓰지 않는다. 주문·결제는 Application Service와 계약으로만 쓴다 (AGENTS.md 5절)");

    @ArchTest
    static final ArchRule dropUsesPaymentOnlyThroughContract = noClasses()
            .that().resideInAPackage("com.restless.drop..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.restless.payment.api..", "com.restless.payment.application.pg..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.restless.payment.application.PaymentService")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.restless.payment.domain.Payment")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.restless.payment.domain.PaymentRefund")
            .because("드롭은 결제 결과를 PaymentResultHandler 계약으로만 받는다. PG를 직접 부르지 않는다");

    @ArchTest
    static final ArchRule dropDoesNotTouchOrderEntities = noClasses()
            .that().resideInAPackage("com.restless.drop..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.restless.order.domain.Order")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.restless.order.domain.OrderItem")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.restless.order.domain.OrderShippingSnapshot")
            .because("주문 엔티티는 주문 모듈 밖으로 나오지 않는다. OrderService와 OrderView를 쓴다");
}
