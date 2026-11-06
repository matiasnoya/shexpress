package com.shopexpress.pedidos.saga;

import com.shopexpress.pedidos.cadena.ManejadorPedido;
import com.shopexpress.pedidos.dto.Dtos.CrearPedidoRequest;
import com.shopexpress.pedidos.error.Errores;
import com.shopexpress.pedidos.modelo.Pedido;
import com.shopexpress.pedidos.modelo.Pedido.Estado;
import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Resultado;
import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Tipo;
import com.shopexpress.pedidos.modelo.Pedido.Saga.EstadoSaga;
import com.shopexpress.pedidos.saga.ContextoPedido.Compensacion;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Orquestador central de la SAGA "crear pedido".
 *
 * <ol>
 *   <li>Idempotencia (Redis + indice unico) y alta del pedido PENDIENTE -> PROCESANDO.</li>
 *   <li>Ejecuta la Chain of Responsibility; cada eslabon con efecto apila su compensacion.</li>
 *   <li>Si un eslabon falla: ejecuta las compensaciones en orden inverso (LIFO), registra cada
 *       una en el log de la SAGA y deja el pedido RECHAZADO con su motivo.</li>
 * </ol>
 * Cada cambio de estado se persiste en MongoDB con writeConcern majority, asi el log de la SAGA
 * sobrevive a una caida del primario.
 */
@Service
public class OrquestadorSagaPedido {

    private static final Logger log = LoggerFactory.getLogger(OrquestadorSagaPedido.class);
    private static final int INTENTOS_COMPENSACION = 3;

    private final MongoTemplate mongo;
    private final ManejadorPedido cadena;
    private final IdempotenciaService idempotencia;
    private final boolean simularFalloHistorialGlobal;

    public OrquestadorSagaPedido(MongoTemplate mongo,
                                 @Qualifier("cadenaPedido") ManejadorPedido cadena,
                                 IdempotenciaService idempotencia,
                                 @Value("${shopexpress.simular-fallo-historial:false}") boolean simularFalloHistorialGlobal) {
        this.mongo = mongo;
        this.cadena = cadena;
        this.idempotencia = idempotencia;
        this.simularFalloHistorialGlobal = simularFalloHistorialGlobal;
    }

    /** @param repetido true si la Idempotency-Key ya se habia usado (no se ejecuto nada nuevo). */
    public record ResultadoSaga(Pedido pedido, boolean repetido) {
    }

    public ResultadoSaga crearPedido(String idempotencyKey, CrearPedidoRequest req, boolean simularFalloHistorial) {
        String pedidoId = "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Barrera 1: Redis
        Optional<String> previo = idempotencia.reclamar(idempotencyKey, pedidoId);
        if (previo.isPresent()) {
            return new ResultadoSaga(buscarRepetido(previo.get(), idempotencyKey), true);
        }

        // Paso 1 de la SAGA: alta del pedido. Barrera 2: indice unico en idempotency_key
        Pedido pedido = Pedido.nuevo(pedidoId, idempotencyKey, req.usuarioId(), req.pago() == null ? null : req.pago().metodo());
        pedido.registrarPaso("CREAR_PEDIDO", Tipo.ACCION, Resultado.OK, "Pedido registrado en estado PENDIENTE");
        try {
            mongo.insert(pedido);
        } catch (DuplicateKeyException e) {
            return new ResultadoSaga(buscarRepetido(null, idempotencyKey), true);
        }
        pedido.setEstado(Estado.PROCESANDO);

        ContextoPedido ctx = new ContextoPedido(req, pedido, simularFalloHistorial || simularFalloHistorialGlobal, mongo::save);
        try {
            cadena.manejar(ctx);
            log.info("SAGA {} completada", pedidoId);
        } catch (RechazoPedidoException e) {
            compensar(ctx, e.getCodigo(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Error inesperado en SAGA {}", pedidoId, e);
            compensar(ctx, "ERROR_INTERNO", e.getMessage());
        }
        return new ResultadoSaga(pedido, false);
    }

    public Pedido obtener(String pedidoId) {
        Pedido p = mongo.findById(pedidoId, Pedido.class); // lectura en primario (CP)
        if (p == null) {
            throw Errores.noEncontrado("Pedido " + pedidoId + " no existe");
        }
        return p;
    }

    private void compensar(ContextoPedido ctx, String codigo, String motivo) {
        Pedido pedido = ctx.pedido();
        log.warn("SAGA {} fallo ({}): {}. Compensaciones pendientes: {}", pedido.getPedidoId(), codigo, motivo,
                ctx.compensaciones().size());
        pedido.rechazar(codigo, motivo);
        boolean huboEfectos = !ctx.compensaciones().isEmpty();
        boolean todasOk = true;
        while (!ctx.compensaciones().isEmpty()) {
            Compensacion c = ctx.compensaciones().pop();
            String error = ejecutarConReintentos(c);
            if (error == null) {
                pedido.registrarPaso(c.paso(), Tipo.COMPENSACION, Resultado.OK, c.descripcion());
            } else {
                todasOk = false;
                pedido.registrarPaso(c.paso(), Tipo.COMPENSACION, Resultado.ERROR, error);
                pedido.getSaga().getCompensacionesPendientes().add(c.paso());
            }
            mongo.save(pedido);
        }
        pedido.getSaga().setEstado(!huboEfectos ? EstadoSaga.ABORTADA
                : todasOk ? EstadoSaga.COMPENSADA : EstadoSaga.COMPENSACION_FALLIDA);
        mongo.save(pedido);
    }

    /** @return null si la compensacion se ejecuto; el mensaje de error si fallo todos los intentos. */
    private String ejecutarConReintentos(Compensacion c) {
        RuntimeException ultimo = null;
        for (int i = 1; i <= INTENTOS_COMPENSACION; i++) {
            try {
                c.accion().run();
                return null;
            } catch (RuntimeException e) {
                ultimo = e;
                log.warn("Compensacion {} fallo (intento {}/{}): {}", c.paso(), i, INTENTOS_COMPENSACION, e.getMessage());
                try {
                    Thread.sleep(200L * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return ultimo == null ? "interrumpida" : ultimo.getMessage();
    }

    private Pedido buscarRepetido(String pedidoId, String idempotencyKey) {
        Pedido p = pedidoId != null ? mongo.findById(pedidoId, Pedido.class) : null;
        if (p == null) {
            p = mongo.findOne(Query.query(Criteria.where("idempotency_key").is(idempotencyKey)), Pedido.class);
        }
        if (p == null) {
            throw Errores.conflicto("Ya hay un pedido en proceso con esa Idempotency-Key; reintente en unos segundos");
        }
        return p;
    }
}
