package edu.cwru.openbench.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/** The single-page fallback: client routes get index.html, API paths never do. */
class SinglePageAppResourceResolverTest {

    @TempDir
    Path client;

    private Resource location;
    private final WebConfig.SinglePageAppResourceResolver resolver = new WebConfig.SinglePageAppResourceResolver();

    @BeforeEach
    void buildAClient() throws IOException {
        Files.writeString(client.resolve("index.html"), "<!doctype html>");
        Files.createDirectories(client.resolve("assets"));
        Files.writeString(client.resolve("assets/app.js"), "console.log()");
        location = new FileSystemResource(client.toString() + "/");
    }

    @Test
    void servesAStaticFileAsItIs() throws IOException {
        assertThat(resolver.getResource("assets/app.js", location).getFilename()).isEqualTo("app.js");
    }

    @Test
    void servesIndexForAClientRoute() throws IOException {
        assertThat(resolver.getResource("requests/42", location).getFilename()).isEqualTo("index.html");
        assertThat(resolver.getResource("login", location).getFilename()).isEqualTo("index.html");
    }

    @Test
    void neverServesIndexForAnApiPath() throws IOException {
        assertThat(resolver.getResource("api/teams", location)).isNull();
        assertThat(resolver.getResource("api", location)).isNull();
    }
}
