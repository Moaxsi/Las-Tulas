package com.lastulas.ica.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Consulta de DNI y RUC contra Decolecta.
 *
 * El navegador llama a ESTE endpoint y es el backend quien habla con Decolecta
 * usando el token guardado en el servidor. El token nunca sale de aqui, que es
 * justamente lo que el punto 9 viene a cerrar.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class DocumentoController {

    private static final Logger log = LoggerFactory.getLogger(DocumentoController.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    private final String decolectaToken;

    @Autowired
    public DocumentoController(@Value("${decolecta.token:}") String decolectaToken) {
        this(decolectaToken, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8)).build());
    }

    DocumentoController(String decolectaToken, HttpClient client) {
        this.decolectaToken = decolectaToken == null ? "" : decolectaToken.trim();
        this.client = client;
        if (this.decolectaToken.isBlank()) {
            log.warn("Consulta DNI/RUC deshabilitada: configura DECOLECTA_TOKEN en el entorno del servidor y reinicia el servicio.");
        }
    }

    @GetMapping("/documento")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public ResponseEntity<?> consultarDocumento(@RequestParam String tipo, @RequestParam String numero) {
        String tipoNormalizado = tipo.toLowerCase(Locale.ROOT);
        int largoEsperado = "dni".equals(tipoNormalizado) ? 8 : 11;
        if ((!tipoNormalizado.equals("dni") && !tipoNormalizado.equals("ruc"))
                || numero == null || !numero.matches("\\d{" + largoEsperado + "}")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Tipo o numero de documento invalido."));
        }

        if (decolectaToken == null || decolectaToken.isBlank()) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "La consulta DNI/RUC no está configurada. Contacta al administrador o ingresa el nombre manualmente."));
        }

        try {
            String url = "dni".equals(tipoNormalizado)
                    ? "https://api.decolecta.com/v1/reniec/dni?numero=" + numero
                    : "https://api.decolecta.com/v1/sunat/ruc?numero=" + numero;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + decolectaToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return respuestaErrorProveedor(response.statusCode());
            }

            Map<String, Object> data = mapper.readValue(response.body(), new TypeReference<>() {});
            if (data == null) {
                log.warn("Decolecta devolvio un cuerpo JSON vacio.");
                return respuestaErrorConsulta();
            }

            String nombre = "dni".equals(tipoNormalizado) ? nombreDesdeDni(data) : nombreDesdeRuc(data);

            if (nombre == null || nombre.isBlank()) {
                return ResponseEntity.status(404)
                        .body(Map.of("success", false, "message", "No se encontro informacion para ese numero."));
            }

            Map<String, Object> respuesta = new HashMap<>();
            respuesta.put("success", true);
            respuesta.put("nombre", nombre);
            return ResponseEntity.ok(respuesta);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Consulta a Decolecta interrumpida.");
            return respuestaErrorConsulta();
        } catch (IOException e) {
            log.warn("Fallo de red o respuesta invalida de Decolecta ({})", e.getClass().getSimpleName());
            return respuestaErrorConsulta();
        }
    }

    private static ResponseEntity<Map<String, Object>> respuestaErrorConsulta() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "success", false,
                "message", "No se pudo completar la consulta de Decolecta. Puedes escribir el nombre manualmente."));
    }

    static ResponseEntity<Map<String, Object>> respuestaErrorProveedor(int statusCode) {
        if (statusCode == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "No se encontro informacion para ese numero."));
        }
        if (statusCode == 401 || statusCode == 403) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "success", false,
                    "message", "El servicio de consulta rechazo las credenciales. Contacta al administrador."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "success", false,
                "message", "El servicio de consulta no esta disponible. Puedes ingresar el nombre manualmente."));
    }

    private String nombreDesdeDni(Map<String, Object> data) {
        String nombre = texto(data, "full_name");
        if (nombre != null && !nombre.isBlank()) return nombre;
        return (texto(data, "first_name") + " "
              + texto(data, "first_last_name") + " "
              + texto(data, "second_last_name")).trim();
    }

    private String nombreDesdeRuc(Map<String, Object> data) {
        String nombre = texto(data, "razon_social");
        if (nombre != null && !nombre.isBlank()) return nombre;
        return texto(data, "nombre_o_razon_social");
    }

    private static String texto(Map<String, Object> data, String campo) {
        Object valor = data.get(campo);
        return valor instanceof String texto ? texto : "";
    }
}
