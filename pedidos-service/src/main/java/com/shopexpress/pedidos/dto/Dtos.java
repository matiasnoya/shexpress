package com.shopexpress.pedidos.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTOs del servicio de Pedidos.
 * El request NO se valida con Bean Validation a proposito: la validacion es el primer eslabon
 * de la Chain of Responsibility ({@code ValidadorDeDatos}), asi queda registrada en la SAGA.
 */
public final class Dtos {

    private Dtos() {
    }

    /** Body de POST /api/pedidos. No incluye precios: el total lo calcula el servidor. */
    public record CrearPedidoRequest(String usuarioId, List<ItemSolicitado> items, PagoSolicitado pago) {
    }

    public record ItemSolicitado(String productoId, Integer cantidad) {
    }

    public record PagoSolicitado(String metodo) {
    }

    // ---- Contratos de los otros microservicios ----

    public record ReservaRemota(String pedidoId, List<ItemReservadoRemoto> items, String estado) {
    }

    public record ItemReservadoRemoto(String productoId, String nombre, int cantidad, BigDecimal precioUnitario) {
    }
}
