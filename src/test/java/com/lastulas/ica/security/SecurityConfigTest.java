package com.lastulas.ica.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void produccionNoArrancaSinSecretoJwtPersistente() {
        SecurityConfig config = new SecurityConfig(
                new DefaultListableBeanFactory().getBeanProvider(JwtAuthFilter.class),
                new ObjectMapper());

        assertThatThrownBy(() -> config.jwtSecretKey("", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_JWT_SECRET");
    }

    @Test
    void permiteLaClaveJwtPersistenteConfigurada() {
        SecurityConfig config = new SecurityConfig(
                new DefaultListableBeanFactory().getBeanProvider(JwtAuthFilter.class),
                new ObjectMapper());

        assertThat(config.jwtSecretKey("secreto-configurado-de-32-caracteres-minimo", true))
                .isNotNull();
    }
}
