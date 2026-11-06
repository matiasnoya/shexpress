package com.shopexpress.catalogo.modelo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Reserva de stock hecha por un pedido. El {@code _id} ES el pedido_id: un pedido
 * no puede reservar dos veces (idempotencia) y la liberacion sabe exactamente que devolver.
 */
@Document("reservas")
public record Reserva(
        @Id String pedidoId,
        List<ItemReservado> items,
        Estado estado,
        Instant creadoEn,
        Instant actualizadoEn) {

    public enum Estado { ACTIVA, LIBERADA }

    /** Snapshot del precio vigente al reservar: es el que usa Pedidos para calcular el total. */
    public record ItemReservado(String productoId, String nombre, int cantidad, BigDecimal precioUnitario) {
    }
}
