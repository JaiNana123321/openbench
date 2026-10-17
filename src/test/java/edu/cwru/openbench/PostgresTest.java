package edu.cwru.openbench;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base class of every integration test: the whole application on PostgreSQL 16 with the production
 * migrations, and MockMvc for HTTP calls.
 *
 * <p>One container serves every test class in the JVM (the singleton pattern: started once here,
 * stopped by Testcontainers at exit), so tests share one database. Create the rows a test needs with
 * unique names ({@link TestData#unique}) and never assume the database is empty.
 *
 * <p>For HTTP calls as a Member, add {@code .with(signedInAs(username))} and, on POST, PUT, and
 * DELETE, {@code .with(xsrf())}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("docker/postgres-init/01-openbench-app.sql"),
                    "/docker-entrypoint-initdb.d/01-openbench-app.sql");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    /** Connected as the schema owner, like Flyway: may write any table. */
    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private UserDetailsService userDetailsService;

    protected TestData testData() {
        return new TestData(jdbc);
    }

    /** Runs a MockMvc request as this Member, with their roles, without the login round trip. */
    protected RequestPostProcessor signedInAs(String username) {
        return SecurityMockMvcRequestPostProcessors.user(userDetailsService.loadUserByUsername(username));
    }

    /**
     * Sends a matching {@code XSRF-TOKEN} cookie and {@code X-XSRF-TOKEN} header, as the web client
     * does on every POST, PUT, and DELETE. Use this, never spring-security-test's {@code csrf()}:
     * that one swaps the CSRF filter's token store in the shared application context, after which
     * no response sets the cookie (ArchitectureTest forbids it).
     */
    protected static RequestPostProcessor xsrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
            cookies[existing.length] = new Cookie("XSRF-TOKEN", token);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
}
