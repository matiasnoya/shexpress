package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.cliente.ClienteHttp.ErrorRemoto;
import com.shopexpress.pedidos.cliente.Clientes.CatalogoCliente;
import com.shopexpress.pedidos.dto.Dtos.ReservaRemota;
import com.shopexpress.pedidos.saga.ContextoPedido;
import com.shopexpress.pedidos.saga.RechazoPedidoException;
import org.springframework.stereotype.Component;

/**
 * Eslabon 3 (paso SAGA con efecto): reserva el stock en el servicio de Catalogo.
 * Valida y reserva en UNA operacion atomica remota, por eso no hay sobreventa.
 *
 * <p>La compensacion (liberar) se apila ANTES de invocar: si la llamada da timeout no sabemos si
 * la reserva se hizo, y liberar es idempotente. Solo se descarta cuando el resultado es un
 * rechazo seguro (409 sin stock / 404 producto inexistente / no se pudo ni conectar).
 */
@Component
public class ValidadorDeStock extends ManejadorPedido {

    private final CatalogoCliente catalogo;

    public ValidadorDeStock(CatalogoCliente catalogo) {
        this.catalogo = catalogo;
    }

    @Override
    protected String paso() {
        return "RESERVAR_STOCK";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        String pedidoId = ctx.pedido().getPedidoId();
        ctx.apilarCompensacion("LIBERAR_STOCK", "Stock devuelto al catalogo", () -> catalogo.liberar(pedidoId));
        try {
            ReservaRemota reserva = catalogo.reservar(pedidoId, ctx.request().items());
            ctx.setReserva(reserva);
            int unidades = reserva.items().stream().mapToInt(i -> i.cantidad()).sum();
            return unidades + " unidad(es) reservadas en " + reserva.items().size() + " producto(s)";
        } catch (ErrorRemoto e) {
            if (e.getStatus() == 409 || e.noEnviada()) {
                ctx.descartarUltimaCompensacion();
                throw new RechazoPedidoException(e.noEnviada() ? "SERVICIO_NO_DISPONIBLE" : "SIN_STOCK", e.getMessage(), e);
            }
            if (e.getStatus() == 404) {
                ctx.descartarUltimaCompensacion();
                throw new RechazoPedidoException("PRODUCTO_INEXISTENTE", e.getMessage(), e);
            }
            throw new RechazoPedidoException("SERVICIO_NO_DISPONIBLE", e.getMessage(), e);
        }
    }
}
