package com.oms.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Spring Security configuration for the reactive gateway.
 *
 * All authentication and authorization is handled by AuthFilter and RateLimitFilter.
 * Spring Security is set to permit all here — the filters do the actual enforcement.
 * This avoids Spring Security interfering with custom filter logic.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                // AuthFilter enforces JWT — Spring Security does not need to re-check
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                // Gateway is stateless — no server-side sessions
                .csrf(csrf -> csrf.disable())
                // No form login — REST API only
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .build();
    }
}
