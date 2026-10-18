package edu.cwru.openbench.config;

import edu.cwru.openbench.common.Actor;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI document at {@code /api/docs} (browsable at {@code /api/docs/ui}). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static {
        // The caller comes from the session, not from the request: keep it out of the document.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class);
    }

    @Bean
    OpenAPI openBenchApi() {
        return new OpenAPI().info(new Info()
                .title("OpenBench API")
                .description("REST API of OpenBench. Errors are RFC 9457 problem details with a `code` field."));
    }
}
