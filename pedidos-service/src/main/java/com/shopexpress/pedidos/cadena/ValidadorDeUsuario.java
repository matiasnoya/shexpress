package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.cliente.ClienteHttp.ErrorRemoto;
import com.shopexpress.pedidos.cliente.Clientes.UsuariosCliente;
import com.shopexpress.pedidos.saga.ContextoPedido;
import com.shopexpress.pedidos.saga.RechazoPedidoException;
import org.springframework.stereotype.Component;

/** Eslabon 2: el usuario debe existir en el servicio de Usuarios. Solo lectura (sin compensacion). */
@Component
public class ValidadorDeUsuario extends ManejadorPedido {

    private final UsuariosCliente usuarios;

    public ValidadorDeUsuario(UsuariosCliente usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    protected String paso() {
        return "VALIDAR_USUARIO";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        String id = ctx.request().usuarioId();
        try {
            if (!usuarios.existe(id)) {
                throw new RechazoPedidoException("USUARIO_INEXISTENTE", "El usuario " + id + " no existe");
            }
        } catch (ErrorRemoto e) {
            throw new RechazoPedidoException("SERVICIO_NO_DISPONIBLE", e.getMessage(), e);
        }
        return "Usuario " + id + " valido";
    }
}
