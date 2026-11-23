package com.lastulas.ica.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentoControllerTest {

    @Test
    void rechazoDeCredencialesDeDecolectaNoExpiraLaSesionDelPos() {
        var respuesta = DocumentoController.respuestaErrorProveedor(401);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(respuesta.getBody())
                .containsEntry("success", false)
                .containsEntry("message",
                        "El servicio de consulta rechazo las credenciales. Contacta al administrador.");
    }

    @Test
    void documentoNoEncontradoSeMantieneComoNotFound() {
        var respuesta = DocumentoController.respuestaErrorProveedor(404);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(respuesta.getBody())
                .containsEntry("success", false)
                .containsEntry("message", "No se encontro informacion para ese numero.");
    }

    @Test
    void otrosErroresDelProveedorNoSeConfundenConAutenticacionDelPos() {
        var respuesta = DocumentoController.respuestaErrorProveedor(500);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void documentoConLongitudInvalidaSeRechazaAntesDeConsultarAlProveedor() {
        var respuesta = new DocumentoController("").consultarDocumento("dni", "1234");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sinTokenDevuelveConfiguracionFaltanteSinConsultarAlProveedor() {
        HttpClient client = mock(HttpClient.class);
        var respuesta = new DocumentoController(" ", client).consultarDocumento("dni", "12345678");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(client);
    }

    @Test
    void springCargaTokenDesdeLaConfiguracionDelServidor() {
        new ApplicationContextRunner().withUserConfiguration(DocumentoController.class)
                .withPropertyValues("decolecta.token=token-de-prueba-en-servidor")
                .run(context -> assertThat(context.getBean(DocumentoController.class))
                        .extracting("decolectaToken").isEqualTo("token-de-prueba-en-servidor"));
    }

    @Test
    void springResuelveLaVariableDeEntornoQueSeConfiguraEnRender() {
        new ApplicationContextRunner().withUserConfiguration(DocumentoController.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.SystemEnvironmentPropertySource("render-test",
                                java.util.Map.of("DECOLECTA_TOKEN", "token-de-prueba-render"))))
                .withPropertyValues("decolecta.token=${DECOLECTA_TOKEN:}")
                .run(context -> assertThat(context.getBean(DocumentoController.class))
                        .extracting("decolectaToken").isEqualTo("token-de-prueba-render"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dniYRucUsanHttpsYTokenSoloEnLaPeticionDelServidor() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> dni = mock(HttpResponse.class);
        HttpResponse<String> ruc = mock(HttpResponse.class);
        when(dni.statusCode()).thenReturn(200);
        when(dni.body()).thenReturn("{\"full_name\":\"Cliente de prueba\"}");
        when(ruc.statusCode()).thenReturn(200);
        when(ruc.body()).thenReturn("{\"razon_social\":\"Empresa de prueba\"}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(dni, ruc);
        var controller = new DocumentoController("  token-de-prueba  ", client);

        assertThat(controller.consultarDocumento("dni", "12345678").getBody())
                .isEqualTo(java.util.Map.of("success", true, "nombre", "Cliente de prueba"));
        assertThat(controller.consultarDocumento("ruc", "20123456789").getBody())
                .isEqualTo(java.util.Map.of("success", true, "nombre", "Empresa de prueba"));
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.uri().getScheme()).isEqualTo("https");
            assertThat(request.uri().getHost()).isEqualTo("api.decolecta.com");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer token-de-prueba");
        });
        assertThat(requests.getAllValues().get(0).uri().getPath()).isEqualTo("/v1/reniec/dni");
        assertThat(requests.getAllValues().get(1).uri().getPath()).isEqualTo("/v1/sunat/ruc");
    }
}
