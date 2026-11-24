package com.restless;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트 JVM 하나가 MySQL 컨테이너 하나를 같이 쓴다. 컨테이너 기동은 한 번에 약 7초라서
 * 컨텍스트마다 새로 띄우면 전체 빌드 시간의 대부분이 여기에 들었다.
 * 대신 Spring 컨텍스트마다 빈 데이터베이스를 새로 만들어 준다. 테스트는 지금처럼 빈 DB에서
 * 시작하고, 앞 컨텍스트가 남긴 데이터를 보지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String IMAGE = "mysql:8.4";
    private static final AtomicInteger NEXT_DATABASE = new AtomicInteger();
    private static final MySQLContainer SHARED = startShared();

    private static MySQLContainer startShared() {
        // 캐시된 컨텍스트마다 커넥션 풀을 하나씩 들고 있다. 기본값 151로는 캐시를 늘리면 접속이 모자란다.
        MySQLContainer container = new MySQLContainer(IMAGE).withCommand("--max-connections=1000");
        // JVM이 끝나면 Testcontainers가 컨테이너를 지운다.
        container.start();
        return container;
    }

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return ContextDatabase.create("ctx_" + NEXT_DATABASE.incrementAndGet());
    }

    /**
     * 공유 컨테이너 안의 데이터베이스 하나를 컨테이너처럼 보여 준다. getJdbcUrl()이 이 데이터베이스를
     * 가리키므로 MySQLContainer를 주입받아 DATABASE()를 쓰는 테스트도 그대로 동작한다.
     * Spring이 부르는 start()는 아무것도 하지 않고, 컨텍스트가 닫히며 부르는 stop()은 데이터베이스만 지운다.
     */
    static final class ContextDatabase extends MySQLContainer {

        private final String database;

        private ContextDatabase(String database) {
            super(IMAGE);
            this.database = database;
        }

        static ContextDatabase create(String database) {
            rootUpdate("CREATE DATABASE " + database);
            rootUpdate("GRANT ALL PRIVILEGES ON " + database + ".* TO '" + SHARED.getUsername() + "'@'%'");
            return new ContextDatabase(database);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            rootUpdate("DROP DATABASE IF EXISTS " + database);
        }

        @Override
        public boolean isRunning() {
            return SHARED.isRunning();
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:mysql://" + SHARED.getHost() + ":" + SHARED.getMappedPort(MYSQL_PORT) + "/" + database;
        }

        @Override
        public String getDatabaseName() {
            return database;
        }

        @Override
        public String getUsername() {
            return SHARED.getUsername();
        }

        @Override
        public String getPassword() {
            return SHARED.getPassword();
        }

        private static void rootUpdate(String sql) {
            try (var connection = DriverManager.getConnection(SHARED.getJdbcUrl(), "root", SHARED.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("테스트 데이터베이스 준비 실패: " + sql, e);
            }
        }
    }
}
