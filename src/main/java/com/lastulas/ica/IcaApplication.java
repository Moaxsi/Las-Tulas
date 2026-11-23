package com.lastulas.ica;

import java.time.ZoneId;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IcaApplication {

	public static void main(String[] args) {
		// Ventas, caja y reportes deben usar la hora de Perú también en la nube.
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("America/Lima")));
		SpringApplication.run(IcaApplication.class, args);
	}
	// =========================================================================
	// Para conectar desde el móvil mediante túnel Pinggy (en una terminal separada):
	//
	// 1. Con código QR listo para escanear con la cámara del celular:
	//    ssh -p 443 -R 0:localhost:8080 -o ServerAliveInterval=30 qr@a.pinggy.io
	//
	// 2. O ejecutando el script ./pinggy.sh incluido en la raíz del proyecto.
	// =========================================================================
}
