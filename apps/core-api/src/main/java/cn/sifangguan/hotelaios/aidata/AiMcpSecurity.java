package cn.sifangguan.hotelaios.aidata;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AiMcpSecurity {
    // Only this exact endpoint uses a scoped MCP credential. Every request is
    // authenticated by AiMcpController; it never accepts browser cookies or JWTs.
    @Bean @Order(1)
    SecurityFilterChain aiMcpFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/ai-data/mcp")
            .csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).logout(l->l.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
    }
}
