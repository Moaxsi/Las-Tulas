package com.lastulas.ica.security;

import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectProvider<JwtAuthFilter> jwtAuthFilterProvider;
    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectProvider<JwtAuthFilter> jwtAuthFilterProvider, ObjectMapper objectMapper) {
        this.jwtAuthFilterProvider = jwtAuthFilterProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Evita que Spring Boot registre JwtAuthFilter automáticamente en el contenedor Tomcat.
     * Debe ejecutarse ÚNICAMENTE dentro de la cadena de Spring Security para no perder el SecurityContext.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecretKey jwtSecretKey(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.require-configured-secret:false}") boolean requireConfiguredSecret) {
        if (secret == null || secret.isBlank()) {
            if (requireConfiguredSecret) {
                throw new IllegalStateException("APP_JWT_SECRET es obligatorio y debe tener al menos 32 caracteres.");
            }
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            return new SecretKeySpec(randomBytes, "HmacSHA256");
        }

        byte[] keyBytes = secret.trim().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET debe tener al menos 32 caracteres.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthFilter jwtFilter = jwtAuthFilterProvider.getObject();
        http
            // 1. Desactivar CSRF para permitir POST /api/login desde Pinggy sin error 403
            .csrf(csrf -> csrf.disable())

            // 2. Habilitar CORS para dominios externos (*.pinggy-free.link / *.pinggy.net)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 3. Sesiones sin estado (JWT)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 4. Rutas públicas y protegidas
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/login", "/api/config").permitAll()
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/*.html",
                    "/*.css",
                    "/*.js",
                    "/*.jpg",
                    "/*.JPG",
                    "/*.jpeg",
                    "/*.JPEG",
                    "/*.png",
                    "/*.PNG",
                    "/*.svg",
                    "/*.ico",
                    "/favicon.ico"
                ).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )

            // 5. Respuestas de error en formato JSON
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    if (objectMapper != null) {
                        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                            "success", false,
                            "message", "No autorizado"
                        )));
                    }
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    if (objectMapper != null) {
                        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                            "success", false,
                            "message", "Acceso denegado"
                        )));
                    }
                })
            );

        if (jwtFilter != null) {
            http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(Arrays.asList("Authorization", "X-Pinggy-No-Screen"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
