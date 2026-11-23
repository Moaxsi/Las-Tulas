package com.lastulas.ica;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class IcaApplicationTest {

    @Test
    void configuraHoraPeruAntesDeIniciarSpringAunqueElServidorUseUtc() {
        TimeZone anterior = TimeZone.getDefault();
        ZoneId peru = ZoneId.of("America/Lima");
        String[] args = {};
        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            spring.when(() -> SpringApplication.run(IcaApplication.class, args)).thenAnswer(invocacion -> {
                assertThat(ZoneId.systemDefault()).isEqualTo(peru);
                LocalDateTime antes = LocalDateTime.now(peru);
                Venta venta = new Venta();
                CajaTurno turno = new CajaTurno();
                Auditoria auditoria = new Auditoria();
                LocalDateTime despues = LocalDateTime.now(peru);
                assertThat(venta.getFechaVenta()).isBetween(antes, despues);
                assertThat(turno.getFechaApertura()).isBetween(antes, despues);
                assertThat(auditoria.getFecha()).isBetween(antes, despues);
                assertThat(LocalDate.now()).isEqualTo(LocalDate.now(peru));
                return null;
            });

            IcaApplication.main(args);

            spring.verify(() -> SpringApplication.run(IcaApplication.class, args));
        } finally {
            TimeZone.setDefault(anterior);
        }
    }
}
