package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.modelo.Pedido.Estado;
import com.shopexpress.pedidos.modelo.Pedido.Saga.EstadoSaga;
import com.shopexpress.pedidos.saga.ContextoPedido;
import org.springframework.stereotype.Component;

/** Eslabon 7 (ultimo): todos los pasos salieron bien, el pedido queda CONFIRMADO. */
@Component
public class ConfirmadorDePedido extends ManejadorPedido {

    @Override
    protected String paso() {
        return "CONFIRMAR_PEDIDO";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        ctx.pedido().setEstado(Estado.CONFIRMADO);
        ctx.pedido().getSaga().setEstado(EstadoSaga.COMPLETADA);
        return "Pedido confirmado";
    }
}
