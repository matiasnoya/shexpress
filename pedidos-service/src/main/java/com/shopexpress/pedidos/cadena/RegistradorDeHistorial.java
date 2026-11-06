package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.cliente.ClienteHttp.ErrorRemoto;
import com.shopexpress.pedidos.cliente.Clientes.UsuariosCliente;
import com.shopexpress.pedidos.modelo.Pedido;
import com.shopexpress.pedidos.saga.ContextoPedido;
import com.shopexpress.pedidos.saga.RechazoPedidoException;
import org.springframework.stereotype.Component;

/**
 * Eslabon 6 (paso SAGA con efecto): agrega el pedido al historial de compras del usuario.
 * Compensacion: quitarlo. Se puede forzar su fallo (header X-Simular-Fallo: HISTORIAL o la
 * variable SIMULAR_FALLO_HISTORIAL) para demostrar la compensacion en cascada (reembolso + stock).
 */
@Component
public class RegistradorDeHistorial extends ManejadorPedido {

    private final UsuariosCliente usuarios;

    public RegistradorDeHistorial(UsuariosCliente usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    protected String paso() {
        return "REGISTRAR_HISTORIAL";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        Pedido p = ctx.pedido();
        if (ctx.simularFalloHistorial()) {
            throw new RechazoPedidoException("FALLO_HISTORIAL", "Fallo simulado al registrar el historial del usuario");
        }
        ctx.apilarCompensacion("QUITAR_HISTORIAL", "Pedido quitado del historial del usuario",
                () -> usuarios.quitarCompra(p.getUsuarioId(), p.getPedidoId()));
        try {
            usuarios.registrarCompra(p.getUsuarioId(), p.getPedidoId(), p.getTotal());
        } catch (ErrorRemoto e) {
            if (e.noEnviada() || (e.getStatus() >= 400 && e.getStatus() < 500)) {
                ctx.descartarUltimaCompensacion(); // seguro que no se registro nada
            }
            throw new RechazoPedidoException("SERVICIO_NO_DISPONIBLE", e.getMessage(), e);
        }
        return "Pedido agregado al historial de " + p.getUsuarioId();
    }
}
