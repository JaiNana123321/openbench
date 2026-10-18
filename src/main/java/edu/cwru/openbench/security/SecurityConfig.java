package edu.cwru.openbench.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Session login for the same-origin client (design Section 7, Authentication and Sessions).
 *
 * <ul>
 *   <li>POST /api/login (form fields {@code username}, {@code password}) answers 200 with the body of
 *       GET /api/me, or 401 {@code BAD_CREDENTIALS}; POST /api/logout answers 204.
 *   <li>Everything under /api needs a signed-in Member: 401 {@code UNAUTHENTICATED}, never a redirect.
 *       Roles are checked by {@code @PreAuthorize} on the services (403 {@code FORBIDDEN}).
 *   <li>CSRF is cookie to header: script reads the cookie {@code XSRF-TOKEN} and sends it back in
 *       {@code X-XSRF-TOKEN} on every POST, PUT, and DELETE, the login included (403
 *       {@code CSRF_REJECTED} otherwise).
 *   <li>Paths outside /api are the client's and are open.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginHandler login, SecurityProblems problems) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        // The client's sign-in route; naming it also turns off Spring's generated page.
                        .loginPage("/login")
                        .loginProcessingUrl("/api/login")
                        .successHandler(login)
                        .failureHandler(login))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SinglePageAppCsrfTokenRequestHandler()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                // A 401 answers the call; there is no page to return to after signing in.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .build();
    }

    /** bcrypt, stored with its algorithm id ({@code {bcrypt}...}) so it can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Lets a controller method declare an {@code Actor} parameter to receive the caller. */
    @Bean
    WebMvcConfigurer actorArgument() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new ActorArgumentResolver());
            }
        };
    }
}
