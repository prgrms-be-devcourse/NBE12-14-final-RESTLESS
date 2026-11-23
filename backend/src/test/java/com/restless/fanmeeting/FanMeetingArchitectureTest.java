package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** architecture/overview.md 2절과 fanmeeting/transactions.md 1절의 운영 코드 규칙. */
class FanMeetingArchitectureTest {
    @Test
    void foreignRepositoryIsRejected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ForeignRepository.class);
        assertThatThrownBy(() -> verifyBoundaries(fixture)).isInstanceOf(AssertionError.class);
    }

    @Test
    void systemTimeIsRejected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(SystemTime.class);
        assertThatThrownBy(() -> verifyTime(fixture)).isInstanceOf(AssertionError.class);
    }

    @Test
    void productionFollowsArchitectureRules() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.restless.fanmeeting");
        assertThat(production).as("검사할 팬미팅 운영 클래스").isNotEmpty();
        verifyBoundaries(production);
        verifyTime(production);
    }

    private static void verifyBoundaries(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        classes.forEach(origin -> origin.getDirectDependenciesFromSelf().forEach(dependency -> {
            String target = dependency.getTargetClass().getPackageName();
            if (target.matches("com\\.restless\\.(?!fanmeeting\\.)[^.]+\\.infrastructure(?:\\..*)?")) {
                violations.add(dependency.getDescription() + " — 다른 도메인의 application 서비스 또는 계약을 사용하세요.");
            }
        }));
        assertThat(violations).as("팬미팅은 다른 도메인의 infrastructure에 직접 의존하지 않습니다").isEmpty();
    }

    private static final Set<String> TIME_TYPES = Set.of(
            "java.time.Instant", "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.Year", "java.time.YearMonth", "java.time.MonthDay");

    private static void verifyTime(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        classes.forEach(origin -> origin.getCodeUnitAccessesFromSelf().forEach(access -> {
            if (readsSystemTime(access.getTarget())) {
                violations.add(access.getDescription() + " — 주입한 Clock 또는 Clock으로 얻은 시각을 전달하세요.");
            }
        }));
        assertThat(violations).as("현재 시각은 주입한 Clock에서 얻습니다").isEmpty();
    }

    private static boolean readsSystemTime(CodeUnitAccessTarget target) {
        String owner = target.getOwner().getName();
        String name = target.getName();
        List<String> parameters = target.getRawParameterTypes().stream().map(type -> type.getName()).toList();
        if (TIME_TYPES.contains(owner) && name.equals("now")) {
            return !parameters.equals(List.of("java.time.Clock"));
        }
        return (owner.equals("java.lang.System") && name.equals("currentTimeMillis"))
                || (owner.equals("java.time.Clock") && name.startsWith("system"))
                || (owner.equals("java.time.InstantSource") && name.equals("system"))
                || (owner.equals("java.util.Calendar") && name.equals("getInstance"))
                || (owner.equals("java.util.Date") && name.equals("<init>") && parameters.isEmpty());
    }

    @Test
    void clockOverloadOwnRepositoryAndElapsedTimeAreAllowed() {
        JavaClasses fixture = new ClassFileImporter().importClasses(Allowed.class);
        verifyBoundaries(fixture);
        verifyTime(fixture);
    }

    @Test
    void timeMethodReferencesAreRejected() {
        JavaClasses fixture = new ClassFileImporter().importClasses(TimeReference.class);
        assertThatThrownBy(() -> verifyTime(fixture)).isInstanceOf(AssertionError.class);
    }

    static class Allowed {
        com.restless.fanmeeting.infrastructure.FanMeetingEventRepository events;
        com.restless.member.application.MemberService members;

        java.time.Instant now(java.time.Clock clock) {
            return java.time.Instant.now(clock);
        }

        long elapsed() {
            return System.nanoTime();
        }
    }

    static class TimeReference {
        java.util.function.Supplier<java.time.Instant> now = java.time.Instant::now;
    }

    @Test
    void alternativeSystemClockSourcesAreRejected() {
        for (Class<?> type : List.of(Millis.class, SystemClock.class, LegacyDate.class,
                CalendarClock.class, LocalClock.class)) {
            JavaClasses fixture = new ClassFileImporter().importClasses(type);
            assertThatThrownBy(() -> verifyTime(fixture)).as(type.getSimpleName())
                    .isInstanceOf(AssertionError.class);
        }
    }

    static class Millis {
        long now() { return System.currentTimeMillis(); }
    }

    static class SystemClock {
        java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    }

    static class LegacyDate {
        java.util.Date now() { return new java.util.Date(); }
    }

    static class CalendarClock {
        java.util.Calendar now() { return java.util.Calendar.getInstance(); }
    }

    static class LocalClock {
        java.time.LocalDate now() { return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")); }
    }

    static class ForeignRepository {
        com.restless.member.infrastructure.MemberRepository repository;
    }

    static class SystemTime {
        java.time.Instant now() {
            return java.time.Instant.now();
        }
    }
}
