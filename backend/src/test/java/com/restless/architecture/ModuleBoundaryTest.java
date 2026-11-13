package com.restless.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 모든 모듈 공통 경계 (AGENTS.md 5절 "Do not access another domain's repository directly").
 * 모듈은 {@code com.restless.<모듈>} 바로 아래 패키지다. 새 모듈도 자동으로 검사된다.
 * 결제·주문·드롭의 세부 규칙은 {@link OwnedModuleBoundaryTest}에 있다.
 */
@AnalyzeClasses(packages = "com.restless", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String ROOT = "com.restless.";

    @ArchTest
    static final ArchRule noModuleUsesAnotherModulesInfrastructure = classes()
            .that().resideInAPackage("com.restless..")
            .should(notDependOnAnotherModulesInfrastructure())
            .because("다른 모듈의 Repository(infrastructure)를 직접 쓰지 않는다. "
                    + "그 모듈의 application 서비스나 계약으로만 연결한다 (AGENTS.md 5절)");

    private static ArchCondition<JavaClass> notDependOnAnotherModulesInfrastructure() {
        return new ArchCondition<>("not depend on another module's infrastructure package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = module(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getBaseComponentType().getPackageName();
                    String to = module(targetPackage);
                    boolean foreignInfrastructure = to != null && !to.equals(from)
                            && (targetPackage + ".").startsWith(ROOT + to + ".infrastructure.");
                    if (foreignInfrastructure) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** {@code com.restless.drop.application} → {@code drop}. Outside a module → null. */
    private static String module(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return null;
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
