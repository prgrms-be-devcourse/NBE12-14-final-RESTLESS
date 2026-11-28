# 백엔드 실행 이미지. 빌드 컨텍스트는 backend/ 이다.
#   docker build -t restless-backend backend
# 테스트는 CI가 먼저 돌린다. 이미지 빌드에서는 실행 JAR만 만든다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test \
 && cp build/libs/$(ls build/libs | grep -v -- '-plain.jar$' | head -n 1) application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
# 자주 바뀌지 않는 의존성 계층을 먼저 복사해 이미지 계층 캐시를 살린다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app

# 컨테이너 메모리의 60%까지만 힙으로 쓴다. t3.small(2GB)에서 OS와 도커 몫을 남기기 위해서다.
# CompileThresholdScaling=0.1은 JIT 컴파일 문턱을 10분의 1로 낮춰 판매 전 예열 동안 컴파일을 끝낸다 (#552).
# 2코어 서버에서 판매 중 앱 CPU의 45%가 JIT였다. 구조적 해결이 아니라 JVM 튜닝이다.
# 실행할 때 -e JAVA_TOOL_OPTIONS=... 나 env 파일로 바꿀 수 있다. 그러면 이 값 전체를 대체하므로 필요한 옵션을 함께 넣는다.
# 시간대는 테스트와 같은 UTC다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -XX:CompileThresholdScaling=0.1" \
    TZ=UTC
# 8080 서비스, 8081 관리 포트 (perf 프로필의 지표와 health)
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "application.jar"]
