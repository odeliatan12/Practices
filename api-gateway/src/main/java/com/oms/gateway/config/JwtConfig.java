package com.oms.gateway.config;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Configures JWT decoding.
 *
 * Dev profile  → HS256 with a shared secret (simple, no key files needed)
 * Prod profile → switch to RS256 with the user-service public key:
 *
 *   spring:
 *     security:
 *       oauth2:
 *         resourceserver:
 *           jwt:
 *             jwk-set-uri: http://user-service:8086/auth/jwks   ← user-service exposes JWKS
 *
 * and replace this bean with:
 *   return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
 */
@Configuration
public class JwtConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        byte[] keyBytes = jwtSecret.getBytes();
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusReactiveJwtDecoder
                .withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
