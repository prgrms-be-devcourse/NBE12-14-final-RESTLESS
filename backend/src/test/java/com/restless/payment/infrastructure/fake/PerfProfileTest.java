package com.restless.payment.infrastructure.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.application.pg.PgClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** Load-test server profile: fake PG only, metrics on a separate management port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_URL=unused-service-connection", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "JWT_SECRET=perf-profile-test-secret-0123456789abcdef",
        "MANAGEMENT_PORT=0",
})
@ActiveProfiles("perf")
@Import(TestcontainersConfiguration.class)
class PerfProfileTest {
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired PgClient pgClient;
    @Autowired Environment environment;
    @Value("${local.server.port}") int serverPort;
    @Value("${local.management.port}") int managementPort;

    @Test
    void usesTheFakePg() {
        assertThat(pgClient).isInstanceOf(FakePgClient.class);
    }

    @Test
    void exposesLoadTestMetricsOnlyOnTheManagementPort() throws Exception {
        assertThat(get(serverPort, "/api/drops").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);

        var metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains(
                "hikaricp_connections_pending", "hikaricp_connections_acquire_seconds",
                "jvm_gc_", "process_cpu_usage", "http_server_requests_seconds_bucket");
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isNotEqualTo(200);
    }

    /**
     * Tomcat thread metrics need the Tomcat MBean registry. Tomcat disables that registry JVM-wide and for good
     * once any earlier test context starts with the default, so the shared test JVM cannot show
     * {@code tomcat_threads_busy_threads}. A real perf server is the only context in its JVM; check the switch.
     */
    @Test
    void enablesTheTomcatRegistryForThreadMetrics() {
        assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled", Boolean.class)).isTrue();
    }

    @Test
    void guardRejectsARealPg() {
        assertThatThrownBy(() -> new PerfProfilePgGuard(mock(PgClient.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pg.provider=fake");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
