package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Resultado;
import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Tipo;
import com.shopexpress.pedidos.saga.ContextoPedido;

/**
 * Eslabon de la Chain of Responsibility del servicio de Pedidos.
 *
 * <p>Cada manejador hace UNA cosa y, si sale bien, delega en el siguiente. Si falla lanza
 * {@link com.shopexpress.pedidos.saga.RechazoPedidoException}: la cadena se corta y el orquestador
 * de la SAGA ejecuta las compensaciones. El metodo plantilla {@link #manejar} registra el
 * resultado de cada eslabon en el log de la SAGA del pedido.
 */
public abstract class ManejadorPedido {

    private ManejadorPedido siguiente;

    /** Enlaza el siguiente eslabon y lo devuelve, para armar la cadena de forma fluida. */
    public ManejadorPedido enlazar(ManejadorPedido siguiente) {
        this.siguiente = siguiente;
        return siguiente;
    }

    public final void manejar(ContextoPedido ctx) {
        String detalle;
        try {
            detalle = procesar(ctx);
        } catch (RuntimeException e) {
            ctx.pedido().registrarPaso(paso(), Tipo.ACCION, Resultado.ERROR, e.getMessage());
            ctx.persistir();
            throw e;
        }
        ctx.pedido().registrarPaso(paso(), Tipo.ACCION, Resultado.OK, detalle);
        ctx.persistir();
        if (siguiente != null) {
            siguiente.manejar(ctx);
        }
    }

    /** Nombre del paso tal como queda en el log de la SAGA. */
    protected abstract String paso();

    /** Logica del eslabon. Devuelve un detalle legible para el log. */
    protected abstract String procesar(ContextoPedido ctx);
}
