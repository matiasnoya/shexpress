package com.shopexpress.pedidos.saga;

import com.shopexpress.pedidos.dto.Dtos.CrearPedidoRequest;
import com.shopexpress.pedidos.dto.Dtos.ReservaRemota;
import com.shopexpress.pedidos.modelo.Pedido;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Estado que viaja por la Chain of Responsibility. Los manejadores son singletons sin estado:
 * todo lo propio de un pedido vive aca.
 *
 * <p>{@code compensaciones} es una PILA: cada manejador que produce un efecto en otro servicio
 * apila su accion inversa; ante un fallo el orquestador las ejecuta en orden inverso (LIFO).
 */
public class ContextoPedido {

    /** Accion inversa de un paso de la SAGA. */
    public record Compensacion(String paso, String descripcion, Runnable accion) {
    }

    private final CrearPedidoRequest request;
    private final Pedido pedido;
    private final boolean simularFalloHistorial;
    private final Consumer<Pedido> persistencia;
    private final Deque<Compensacion> compensaciones = new ArrayDeque<>();
    private ReservaRemota reserva;

    public ContextoPedido(CrearPedidoRequest request, Pedido pedido, boolean simularFalloHistorial,
                          Consumer<Pedido> persistencia) {
        this.request = request;
        this.pedido = pedido;
        this.simularFalloHistorial = simularFalloHistorial;
        this.persistencia = persistencia;
    }

    public CrearPedidoRequest request() { return request; }
    public Pedido pedido() { return pedido; }
    public boolean simularFalloHistorial() { return simularFalloHistorial; }
    public ReservaRemota reserva() { return reserva; }
    public void setReserva(ReservaRemota reserva) { this.reserva = reserva; }

    /** Guarda el pedido (con su log de SAGA) en MongoDB con writeConcern majority. */
    public void persistir() { persistencia.accept(pedido); }

    public void apilarCompensacion(String paso, String descripcion, Runnable accion) {
        compensaciones.push(new Compensacion(paso, descripcion, accion));
    }

    /** Se usa cuando se sabe con certeza que el paso no produjo efecto (ej. 409 sin stock). */
    public void descartarUltimaCompensacion() {
        compensaciones.pop();
    }

    public Deque<Compensacion> compensaciones() { return compensaciones; }
}
