package com.shopexpress.pedidos.pago;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pasarela de pagos simulada (no hay proveedor real). Reglas deterministas para poder
 * demostrar la SAGA con exito y con fallo:
 * <ul>
 *   <li>{@code TARJETA_RECHAZADA} -> rechazado por el emisor.</li>
 *   <li>{@code TARJETA_TIMEOUT} -> la pasarela no responde (falla tecnica).</li>
 *   <li>total mayor al limite configurado -> rechazado por limite de credito.</li>
 *   <li>cualquier otro metodo valido -> aprobado con transaccion_id.</li>
 * </ul>
 */
@Component
public class PasarelaPagoSimulada {

    public static final Set<String> METODOS = Set.of(
            "TARJETA_CREDITO", "TARJETA_DEBITO", "TRANSFERENCIA", "TARJETA_RECHAZADA", "TARJETA_TIMEOUT");

    private static final Logger log = LoggerFactory.getLogger(PasarelaPagoSimulada.class);

    private final BigDecimal limite;

    public PasarelaPagoSimulada(@Value("${shopexpress.pago.limite}") BigDecimal limite) {
        this.limite = limite;
    }

    public record ResultadoPago(boolean aprobado, String transaccionId, String motivo) {
    }

    public ResultadoPago cobrar(String pedidoId, BigDecimal monto, String metodo) {
        if ("TARJETA_TIMEOUT".equals(metodo)) {
            throw new IllegalStateException("La pasarela de pagos no respondio (timeout)");
        }
        if ("TARJETA_RECHAZADA".equals(metodo)) {
            return new ResultadoPago(false, null, "Tarjeta rechazada por el emisor");
        }
        if (monto.compareTo(limite) > 0) {
            return new ResultadoPago(false, null, "Monto " + monto + " supera el limite de " + limite);
        }
        String tx = "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        log.info("Pago aprobado pedido={} monto={} tx={}", pedidoId, monto, tx);
        return new ResultadoPago(true, tx, null);
    }

    /** Compensacion del pago. */
    public void reembolsar(String transaccionId, BigDecimal monto) {
        log.info("Reembolso tx={} monto={}", transaccionId, monto);
    }
}
