package edu.cwru.openbench;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base class of every integration test: the whole application on PostgreSQL 16 with the production
 * migrations, and MockMvc for HTTP calls.
 *
 * <p>One container serves every test class in the JVM (the singleton pattern: started once here,
 * stopped by Testcontainers at exit), so tests share one database. Create the rows a test needs with
 * unique names ({@link TestData#unique}) and never assume the database is empty.
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

    protected TestData testData() {
        return new TestData(jdbc);
    }
}
