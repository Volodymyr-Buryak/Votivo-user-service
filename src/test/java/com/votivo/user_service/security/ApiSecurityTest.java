package com.votivo.user_service.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.votivo.user_service.entity.UserProfile;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.repository.UserProfileRepository;
import com.votivo.user_service.security.config.SecurityConfiguration;
import com.votivo.user_service.security.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.DPoPProofContext;
import org.springframework.security.oauth2.jwt.DPoPProofJwtDecoderFactory;
import org.springframework.security.oauth2.jwt.DPoPProofReplayValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(ApiSecurityTest.TestConfiguration.class)
class ApiSecurityTest {
    private static final String SUBJECT = "3453cf7c-09b1-40b2-a999-4bde563a1623";
    private static final String ME = "/api/v1/users/me";
    private static final String HEADER = "/api/v1/users/me/header";
    private static final String ADMIN = "/api/v1/admin/users";
    private static final RSAKey ISSUER_KEY = issuerKey();
    private static final ECKey PROOF_KEY = proofKey();
    @Autowired WebApplicationContext context;
    @Autowired UserProfileRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(repository);
        when(repository.findById(any())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void acceptsRealEs256ProofAndRejectsReplay() throws Exception {
        String token = token(true, "user", claims -> {});
        String proof = proof(token, ME, "GET", PROOF_KEY, claims -> {});
        mvc.perform(get(ME).header("Authorization", "DPoP " + token).header("DPoP", proof))
                .andExpect(status().isOk());
        mvc.perform(get(ME).header("Authorization", "DPoP " + token).header("DPoP", proof))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectsOwnHeaderWithAuthentication() throws Exception {
        mvc.perform(get(HEADER)).andExpect(status().isUnauthorized());
        mvc.perform(get(HEADER).header("Authorization", "Bearer " + token(false, "user", claims -> {})))
                .andExpect(status().isOk());
    }

    @Test
    void boundedReplayCacheFailsClosedWithoutEvictingLiveProof() throws Exception {
        var cache = new DPoPProofReplayValidator.InMemoryCache();
        cache.setMaxSize(1);
        var factory = new DPoPProofJwtDecoderFactory();
        factory.setJwtValidatorFactory(DPoPProofJwtDecoderFactory.createDefaultJwtValidatorFactory(
                java.util.List.of(new DPoPProofReplayValidator(cache))));
        String token = token(true, "user", claims -> {});
        var decoder = NimbusJwtDecoder.withPublicKey(ISSUER_KEY.toRSAPublicKey()).build();
        var accessToken = decoder.decode(token);
        String first = proof(token, ME, "GET", PROOF_KEY, claims -> {});
        String second = proof(token, ME, "GET", PROOF_KEY, claims -> {});
        var firstContext = DPoPProofContext.withDPoPProof(first).accessToken(accessToken)
                .method("GET").targetUri("http://localhost" + ME).build();
        var secondContext = DPoPProofContext.withDPoPProof(second).accessToken(accessToken)
                .method("GET").targetUri("http://localhost" + ME).build();
        factory.createDecoder(firstContext).decode(first);
        assertThatThrownBy(() -> factory.createDecoder(secondContext).decode(second)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> factory.createDecoder(firstContext).decode(first)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "method", "uri", "hash", "key", "old", "future", "signature", "type", "jti"})
    void rejectsInvalidProofs(String attack) throws Exception {
        String token = token(true, "user", claims -> {});
        ECKey signingKey = attack.equals("key") ? proofKey() : PROOF_KEY;
        String proof = proof(token, attack.equals("uri") ? ADMIN : ME,
                attack.equals("method") ? "PATCH" : "GET", signingKey, claims -> {
                    if (attack.equals("hash")) claims.claim("ath", "wrong");
                    if (attack.equals("old")) claims.issueTime(Date.from(Instant.now().minusSeconds(120)));
                    if (attack.equals("future")) claims.issueTime(Date.from(Instant.now().plusSeconds(120)));
                    if (attack.equals("jti")) claims.jwtID(null);
                }, attack.equals("type") ? "JWT" : "dpop+jwt");
        if (attack.equals("signature")) {
            int signature = proof.lastIndexOf('.') + 1;
            proof = proof.substring(0, signature) + (proof.charAt(signature) == 'A' ? "B" : "A")
                    + proof.substring(signature + 1);
        }
        var request = get(ME).header("Authorization", "DPoP " + token);
        if (!attack.equals("missing")) request.header("DPoP", proof);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("detail").value("Authentication is required."));
    }

    @Test
    void acceptsTheDpopTypeKeycloakGivesABoundAccessToken() throws Exception {
        String token = token(true, "user", claims -> claims.claim("typ", "DPoP"));
        mvc.perform(get(ME).header("Authorization", "DPoP " + token)
                        .header("DPoP", proof(token, ME, "GET", PROOF_KEY, claims -> {})))
                .andExpect(status().isOk());
    }

    @Test
    void boundBearerIsRejectedEvenIfProofHeaderExists() throws Exception {
        String token = token(true, "admin", claims -> {});
        mvc.perform(get(ME).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Bearer " + token)
                        .header("DPoP", proof(token, ME, "GET", PROOF_KEY, claims -> {})))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unboundBearerRemainsAvailableForToolsAndAdminInheritsUser() throws Exception {
        String user = token(false, "user", claims -> {});
        String admin = token(false, "admin", claims -> {});
        mvc.perform(get(ME).header("Authorization", "Bearer " + user)).andExpect(status().isOk());
        mvc.perform(get(HEADER).header("Authorization", "Bearer " + user)).andExpect(status().isOk());
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + user)).andExpect(status().isForbidden());
        mvc.perform(get(ME).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/unmapped").header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden());
        mvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Bearer " + token(false, "ADMIN", claims -> {})))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "audience-missing", "expired", "subject", "short-subject", "subject-missing", "expiry-missing", "unsigned-issuer", "typ-id", "typ-refresh", "typ-missing"})
    void validatesAccessTokenBeforeAuthorization(String attack) throws Exception {
        String token = token(false, "user", claims -> {
            switch (attack) {
                case "issuer" -> claims.issuer("https://wrong.example");
                case "audience" -> claims.audience("account");
                case "audience-missing" -> claims.audience((String) null);
                case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120)));
                case "subject" -> claims.subject("someone");
                case "subject-missing" -> claims.subject(null);
                case "short-subject" -> claims.subject("1-1-1-1-1");
                case "expiry-missing" -> claims.expirationTime(null);
                case "typ-id" -> claims.claim("typ", "ID");
                case "typ-refresh" -> claims.claim("typ", "Refresh");
                case "typ-missing" -> claims.claim("typ", null);
                default -> { }
            }
        }, attack.equals("unsigned-issuer") ? issuerKey() : ISSUER_KEY);
        mvc.perform(get(ME).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void protectsBlockedAndDeletedAdministratorsAndRevokedAdminRole() throws Exception {
        String token = token(false, "admin", claims -> {});
        var account = UserProfile.builder().id(UUID.fromString(SUBJECT)).enabled(false)
                .roles(Set.of(UserRole.admin)).build();
        when(repository.findById(any())).thenReturn(Optional.of(account));
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get(ME).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        account = UserProfile.builder().id(UUID.fromString(SUBJECT)).enabled(true)
                .roles(Set.of(UserRole.admin)).deletedAt(Instant.now()).build();
        when(repository.findById(any())).thenReturn(Optional.of(account));
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        account = UserProfile.builder().id(UUID.fromString(SUBJECT)).enabled(true).roles(Set.of(UserRole.user)).build();
        when(repository.findById(any())).thenReturn(Optional.of(account));
        mvc.perform(get(ADMIN).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get(ME).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void dpopPreservesJwtPrincipalAndEnforcesLocalAdministratorState() throws Exception {
        String token = token(true, "admin", claims -> {});
        mvc.perform(get(ADMIN).header("Authorization", "DPoP " + token)
                        .header("DPoP", proof(token, ADMIN, "GET", PROOF_KEY, claims -> {})))
                .andExpect(status().isOk());
        var account = UserProfile.builder().id(UUID.fromString(SUBJECT)).enabled(true)
                .roles(Set.of(UserRole.user)).build();
        when(repository.findById(any())).thenReturn(Optional.of(account));
        mvc.perform(get(ADMIN).header("Authorization", "DPoP " + token)
                        .header("DPoP", proof(token, ADMIN, "GET", PROOF_KEY, claims -> {})))
                .andExpect(status().isForbidden());
        account.softDelete(Instant.now());
        mvc.perform(get(ME).header("Authorization", "DPoP " + token)
                        .header("DPoP", proof(token, ME, "GET", PROOF_KEY, claims -> {})))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsConfiguredCorsPreflightWithoutCreatingSession() throws Exception {
        mvc.perform(options(ME).header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,dpop,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        var result = mvc.perform(get(ME).header("Authorization", "Bearer " + token(false, "user", claims -> {})))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void databaseOutageFailsClosedWithSanitizedServiceUnavailable() throws Exception {
        when(repository.findById(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "internal database credentials and host"));
        mvc.perform(get(ME).header("Authorization", "Bearer " + token(false, "user", claims -> {})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("status").value(503))
                .andExpect(jsonPath("detail").value("The service is temporarily unavailable."));
    }

    private static String token(boolean bound, String role, Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        return token(bound, role, customizer, ISSUER_KEY);
    }

    private static String token(boolean bound, String role, Consumer<JWTClaimsSet.Builder> customizer,
                                RSAKey signingKey) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("http://localhost:18080/realms/votivo")
                .subject(SUBJECT).audience("user-service").issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer").claim("realm_access", Map.of("roles", Set.of(role)));
        if (bound) claims.claim("cnf", Map.of("jkt", PROOF_KEY.computeThumbprint().toString()));
        customizer.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    private static String proof(String token, String path, String method, ECKey key,
                                Consumer<JWTClaimsSet.Builder> customizer) throws Exception {
        return proof(token, path, method, key, customizer, "dpop+jwt");
    }

    private static String proof(String token, String path, String method, ECKey key,
                                Consumer<JWTClaimsSet.Builder> customizer, String type) throws Exception {
        String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        var claims = new JWTClaimsSet.Builder().issueTime(new Date()).jwtID(UUID.randomUUID().toString())
                .claim("htm", method).claim("htu", "http://localhost" + path).claim("ath", hash);
        customizer.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(type))
                .jwk(key.toPublicJWK()).build(), claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey issuerKey() {
        try { return new RSAKeyGenerator(2048).generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static ECKey proofKey() {
        try { return new ECKeyGenerator(Curve.P_256).generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfiguration.class, AccountStatus.class, Endpoints.class})
    static class TestConfiguration {
        @Bean UserProfileRepository repository() { return mock(UserProfileRepository.class); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().findAndAddModules().build(); }
        @Bean @Primary JwtDecoder signedTokenDecoder(SecurityProperties properties) throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(ISSUER_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                    new AccessTokenValidator(properties.audience())));
            return decoder;
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping({ME, HEADER, ADMIN, "/unmapped"}) String endpoint(
                @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt principal) {
            assertThat(principal).isNotNull();
            assertThat(principal.getSubject()).isEqualTo(SUBJECT);
            return "ok";
        }
    }
}
