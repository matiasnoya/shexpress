package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.modelo.Pedido;
import com.shopexpress.pedidos.modelo.Pedido.Pago.EstadoPago;
import com.shopexpress.pedidos.pago.PasarelaPagoSimulada;
import com.shopexpress.pedidos.pago.PasarelaPagoSimulada.ResultadoPago;
import com.shopexpress.pedidos.saga.ContextoPedido;
import com.shopexpress.pedidos.saga.RechazoPedidoException;
import org.springframework.stereotype.Component;

/** Eslabon 5 (paso SAGA con efecto): cobra el pedido. Compensacion: reembolso. */
@Component
public class ProcesadorDePago extends ManejadorPedido {

    private final PasarelaPagoSimulada pasarela;

    public ProcesadorDePago(PasarelaPagoSimulada pasarela) {
        this.pasarela = pasarela;
    }

    @Override
    protected String paso() {
        return "PROCESAR_PAGO";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        Pedido pedido = ctx.pedido();
        ResultadoPago r;
        try {
            r = pasarela.cobrar(pedido.getPedidoId(), pedido.getTotal(), pedido.getPago().getMetodo());
        } catch (RuntimeException e) {
            pedido.getPago().setEstadoPago(EstadoPago.RECHAZADO);
            throw new RechazoPedidoException("PAGO_NO_DISPONIBLE", e.getMessage(), e);
        }
        if (!r.aprobado()) {
            pedido.getPago().setEstadoPago(EstadoPago.RECHAZADO);
            throw new RechazoPedidoException("PAGO_RECHAZADO", r.motivo());
        }
        pedido.getPago().setEstadoPago(EstadoPago.APROBADO);
        pedido.getPago().setTransaccionId(r.transaccionId());
        ctx.apilarCompensacion("REEMBOLSAR_PAGO", "Pago " + r.transaccionId() + " reembolsado", () -> {
            pasarela.reembolsar(r.transaccionId(), pedido.getTotal());
            pedido.getPago().setEstadoPago(EstadoPago.REEMBOLSADO);
        });
        return "Pago aprobado " + r.transaccionId() + " por " + pedido.getTotal();
    }
}
