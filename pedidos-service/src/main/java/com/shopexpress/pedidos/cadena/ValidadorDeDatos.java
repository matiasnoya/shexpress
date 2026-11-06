package com.shopexpress.pedidos.cadena;

import com.shopexpress.pedidos.dto.Dtos.CrearPedidoRequest;
import com.shopexpress.pedidos.dto.Dtos.ItemSolicitado;
import com.shopexpress.pedidos.pago.PasarelaPagoSimulada;
import com.shopexpress.pedidos.saga.ContextoPedido;
import com.shopexpress.pedidos.saga.RechazoPedidoException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Eslabon 1: valida forma y reglas basicas del pedido. No produce efectos (sin compensacion). */
@Component
public class ValidadorDeDatos extends ManejadorPedido {

    static final int MAX_ITEMS = 50;
    static final int MAX_CANTIDAD = 100;

    @Override
    protected String paso() {
        return "VALIDAR_DATOS";
    }

    @Override
    protected String procesar(ContextoPedido ctx) {
        CrearPedidoRequest req = ctx.request();
        List<String> errores = new ArrayList<>();
        if (req.usuarioId() == null || req.usuarioId().isBlank()) {
            errores.add("usuario_id es obligatorio");
        }
        if (req.items() == null || req.items().isEmpty()) {
            errores.add("items no puede estar vacio");
        } else {
            if (req.items().size() > MAX_ITEMS) {
                errores.add("maximo " + MAX_ITEMS + " items por pedido");
            }
            for (int i = 0; i < req.items().size(); i++) {
                ItemSolicitado it = req.items().get(i);
                if (it == null || it.productoId() == null || it.productoId().isBlank()) {
                    errores.add("items[" + i + "].producto_id es obligatorio");
                }
                if (it == null || it.cantidad() == null || it.cantidad() < 1 || it.cantidad() > MAX_CANTIDAD) {
                    errores.add("items[" + i + "].cantidad debe estar entre 1 y " + MAX_CANTIDAD);
                }
            }
        }
        if (req.pago() == null || req.pago().metodo() == null) {
            errores.add("pago.metodo es obligatorio");
        } else if (!PasarelaPagoSimulada.METODOS.contains(req.pago().metodo())) {
            errores.add("pago.metodo debe ser uno de " + PasarelaPagoSimulada.METODOS);
        }
        if (!errores.isEmpty()) {
            throw new RechazoPedidoException("DATOS_INVALIDOS", String.join("; ", errores));
        }
        return "Datos validos: " + req.items().size() + " item(s), pago " + req.pago().metodo();
    }
}
