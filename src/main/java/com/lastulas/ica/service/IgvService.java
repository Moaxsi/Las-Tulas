package com.lastulas.ica.service;

import com.lastulas.ica.Parametro;
import com.lastulas.ica.ParametroRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tasa de IGV y desglose del total (punto 12).
 *
 * CONVENCION FISCAL
 * -----------------
 * El precio de carta (productos.precio) es el PRECIO FINAL que paga el
 * cliente: ya incluye IGV. Por eso el total que se cobra NO cambia respecto
 * al flujo anterior, y lo que se agrega es el desglose:
 *
 *     total    = suma de precios de carta x cantidad   <- lo que ya se cobraba
 *     subtotal = total / (1 + tasa)                    <- base imponible
 *     igv      = total - subtotal                      <- impuesto
 *
 * Asi la carta no se encarece un 18% de la noche a la manana y el
 * comprobante sale con los dos componentes.
 *
 * La tasa sale de la tabla "parametros" (clave IGV_TASA) y NO del codigo:
 * se cambia con un UPDATE y el backend usa la nueva en la siguiente venta.
 */
@Service
public class IgvService {

    private static final Logger log = LoggerFactory.getLogger(IgvService.class);
    private static final String CLAVE_TASA = "IGV_TASA";

    private final ParametroRepository parametroRepo;
    private final BigDecimal tasaPorDefecto;

    public IgvService(ParametroRepository parametroRepo,
                      @Value("${igv.tasa-por-defecto:0.18}") String tasaPorDefecto) {
        this.parametroRepo = parametroRepo;
        this.tasaPorDefecto = new BigDecimal(tasaPorDefecto);
    }

    /** Tasa vigente, por ejemplo 0.18. */
    public BigDecimal tasaVigente() {
        try {
            Parametro p = parametroRepo.findById(CLAVE_TASA).orElse(null);
            if (p == null || p.getValor() == null || p.getValor().isBlank()) {
                return tasaPorDefecto;
            }
            BigDecimal tasa = new BigDecimal(p.getValor().trim());
            if (tasa.signum() < 0) {
                log.warn("IGV_TASA negativa en parametros ({}). Usando {} por defecto.", p.getValor(), tasaPorDefecto);
                return tasaPorDefecto;
            }
            return tasa;
        } catch (NumberFormatException e) {
            log.warn("IGV_TASA no es un numero valido. Usando {} por defecto.", tasaPorDefecto);
            return tasaPorDefecto;
        }
    }

    /** Total con IGV incluido -> base imponible. */
    public BigDecimal aBaseImponible(BigDecimal total, BigDecimal tasa) {
        return total.divide(BigDecimal.ONE.add(tasa), 2, RoundingMode.HALF_UP);
    }

    /** Base imponible -> impuesto. Always total - subtotal, para que ambos cuadren al centimo. */
    public BigDecimal calcularIgv(BigDecimal total, BigDecimal subtotal) {
        return total.subtract(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal dosDecimales(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
