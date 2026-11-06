package com.shopexpress.pedidos.modelo;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Pedido (agregado raiz del servicio de Pedidos y Pagos).
 * <ul>
 *   <li>{@code items} y {@code pago}: EMBEBIDOS. El precio es un snapshot al momento de la compra
 *       y el pago tiene ciclo de vida 1:1 con el pedido.</li>
 *   <li>{@code usuarioId} / {@code productoId}: REFERENCIAS a entidades de otros microservicios.</li>
 *   <li>{@code saga}: log persistido de los pasos y compensaciones ejecutados (trazabilidad).</li>
 * </ul>
 */
@Document("pedidos")
@JsonPropertyOrder({"pedido_id", "usuario_id", "estado", "codigo_rechazo", "motivo_rechazo", "items", "total",
        "pago", "saga", "idempotency_key", "creado_en", "actualizado_en"})
public class Pedido {

    public enum Estado { PENDIENTE, PROCESANDO, CONFIRMADO, RECHAZADO }

    @Id
    private String pedidoId;
    @Indexed(unique = true)
    private String idempotencyKey;
    @Indexed
    private String usuarioId;
    private Estado estado;
    private List<ItemPedido> items = new ArrayList<>();
    private BigDecimal total;
    private Pago pago;
    private Saga saga = new Saga();
    private String codigoRechazo;
    private String motivoRechazo;
    private Instant creadoEn;
    private Instant actualizadoEn;

    public Pedido() {
    }

    public static Pedido nuevo(String pedidoId, String idempotencyKey, String usuarioId, String metodoPago) {
        Pedido p = new Pedido();
        p.pedidoId = pedidoId;
        p.idempotencyKey = idempotencyKey;
        p.usuarioId = usuarioId;
        p.estado = Estado.PENDIENTE;
        p.pago = new Pago(metodoPago);
        p.creadoEn = Instant.now();
        p.actualizadoEn = p.creadoEn;
        return p;
    }

    public void registrarPaso(String paso, PasoSaga.Tipo tipo, PasoSaga.Resultado resultado, String detalle) {
        saga.pasos.add(new PasoSaga(saga.pasos.size() + 1, paso, tipo, resultado, detalle, Instant.now()));
        actualizadoEn = Instant.now();
    }

    // ---- getters (serializacion JSON) ----
    public String getPedidoId() { return pedidoId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getUsuarioId() { return usuarioId; }
    public Estado getEstado() { return estado; }
    public List<ItemPedido> getItems() { return items; }
    public BigDecimal getTotal() { return total; }
    public Pago getPago() { return pago; }
    public Saga getSaga() { return saga; }
    public String getMotivoRechazo() { return motivoRechazo; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }

    // ---- mutadores usados por la cadena / orquestador ----
    public void setEstado(Estado estado) { this.estado = estado; this.actualizadoEn = Instant.now(); }
    public void setItems(List<ItemPedido> items) { this.items = items; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public void rechazar(String codigo, String motivo) {
        this.codigoRechazo = codigo;
        this.motivoRechazo = motivo;
        setEstado(Estado.RECHAZADO);
    }
    public String getCodigoRechazo() { return codigoRechazo; }

    public record ItemPedido(String productoId, String nombre, int cantidad, BigDecimal precioUnitario, BigDecimal subtotal) {
    }

    public static class Pago {
        public enum EstadoPago { PENDIENTE, APROBADO, RECHAZADO, REEMBOLSADO }

        private String metodo;
        private EstadoPago estadoPago = EstadoPago.PENDIENTE;
        private String transaccionId;

        public Pago() {
        }

        Pago(String metodo) {
            this.metodo = metodo;
        }

        public String getMetodo() { return metodo; }
        public EstadoPago getEstadoPago() { return estadoPago; }
        public String getTransaccionId() { return transaccionId; }
        public void setEstadoPago(EstadoPago estadoPago) { this.estadoPago = estadoPago; }
        public void setTransaccionId(String transaccionId) { this.transaccionId = transaccionId; }
    }

    @JsonPropertyOrder({"estado", "compensaciones_pendientes", "pasos"})
    public static class Saga {
        /**
         * EN_CURSO: ejecutandose. COMPLETADA: todos los pasos OK. ABORTADA: fallo antes de producir
         * efectos (nada que compensar). COMPENSADA: fallo y se revirtieron los efectos.
         * COMPENSACION_FALLIDA: alguna compensacion no pudo ejecutarse (requiere intervencion/reintento).
         */
        public enum EstadoSaga { EN_CURSO, COMPLETADA, ABORTADA, COMPENSADA, COMPENSACION_FALLIDA }

        private EstadoSaga estado = EstadoSaga.EN_CURSO;
        private List<PasoSaga> pasos = new ArrayList<>();
        /** Compensaciones que fallaron y reintenta en segundo plano {@code ReintentadorCompensaciones}. */
        private List<String> compensacionesPendientes = new ArrayList<>();

        public EstadoSaga getEstado() { return estado; }
        public List<PasoSaga> getPasos() { return pasos; }
        public List<String> getCompensacionesPendientes() { return compensacionesPendientes; }
        public void setEstado(EstadoSaga estado) { this.estado = estado; }
    }

    public record PasoSaga(int orden, String paso, Tipo tipo, Resultado resultado, String detalle, Instant fecha) {
        public enum Tipo { ACCION, COMPENSACION }
        public enum Resultado { OK, ERROR }
    }
}
