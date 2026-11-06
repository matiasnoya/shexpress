package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.modelo.Pedido.ItemPedido;
import com.shopexpress.pedidos.saga.ContextoPedido;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Eslabon 4: calcula el total con los precios que devolvio el catalogo al reservar
 * (nunca con precios enviados por el cliente) y los guarda como snapshot en el pedido.
 */
@Component
public class CalculadorDeTotal extends ManejadorPedido {

    @Override
    protected String paso() {
        return "CALCULAR_TOTAL";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        List<ItemPedido> items = ctx.reserva().items().stream()
                .map(i -> new ItemPedido(i.productoId(), i.nombre(), i.cantidad(), i.precioUnitario(),
                        i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())).setScale(2, RoundingMode.HALF_UP)))
                .toList();
        BigDecimal total = items.stream().map(ItemPedido::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        ctx.pedido().setItems(items);
        ctx.pedido().setTotal(total);
        return "Total calculado: " + total;
    }
}
