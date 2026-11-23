package com.votivo.user_service.security.config;

import com.votivo.user_service.security.AccessTokenValidator;
import com.votivo.user_service.security.AccountAccessFilter;
import com.votivo.user_service.security.AccountStatus;
import com.votivo.user_service.security.SecurityProblems;
import jakarta.servlet.DispatcherType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.DPoPAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
    @Bean
    JwtDecoder accessTokenDecoder(SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                new AccessTokenValidator(properties.audience())));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties properties,
                                    JwtDecoder decoder, AccountStatus accountStatus,
                                    JsonMapper mapper
    ) throws Exception {

        SecurityProblems problems = new SecurityProblems(mapper);
        AuthenticationEntryPoint entryPoint = authenticationEntryPoint(problems);
        AccessDeniedHandler deniedHandler =
                (request, response, exception) ->
                        problems.write(response, HttpStatus.FORBIDDEN);
        var proofFactory = proofDecoderFactory(properties);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(config -> config.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(config -> config.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(config -> config
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/me", "/api/v1/users/me/header").hasAnyRole("USER", "ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> {
                    resource.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authenticationConverter()))
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler)
                            .dPoP(dpop -> dpop.authenticationFailureHandler(
                                    (request, response, exception) -> entryPoint.commence(request, response, exception)));
                    resource.addObjectPostProcessor(new ObjectPostProcessor<DPoPAuthenticationProvider>() {
                        @Override
                        public <O extends DPoPAuthenticationProvider> O postProcess(O provider) {
                            provider.setDPoPProofVerifierFactory(proofFactory);
                            return provider;
                        }
                    });
                })
                .addFilterBefore(new AccountAccessFilter(accountStatus, problems), AuthorizationFilter.class);

        return http.build();
    }

    private static AuthenticationEntryPoint authenticationEntryPoint(SecurityProblems problems) {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "DPoP, Bearer");
            problems.write(response, HttpStatus.UNAUTHORIZED);
        };
    }

    private static DPoPProofJwtDecoderFactory proofDecoderFactory(SecurityProperties properties) {
        var replayCache = new DPoPProofReplayValidator.InMemoryCache();
        replayCache.setMaxSize(properties.dpopReplayCacheSize());
        replayCache.setMaxRequestsPerKey(properties.dpopMaxRequestsPerKey());
        var factory = new DPoPProofJwtDecoderFactory();
        factory.setJwtValidatorFactory(DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(
                List.of(new DPoPProofReplayValidator(replayCache))));
        return factory;
    }

    private static JwtAuthenticationConverter authenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> realmAuthorities(jwt.getClaim("realm_access")));
        return converter;
    }

    private static UrlBasedCorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "DPoP"));
        cors.setExposedHeaders(List.of("WWW-Authenticate"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static Collection<GrantedAuthority> realmAuthorities(Object realmAccess) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        if (realmAccess instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles) {
            if (roles.contains("admin")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            } else if (roles.contains("user")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            }
        }
        return authorities;
    }
}
