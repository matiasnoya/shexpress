package com.shopexpress.catalogo.servicio;

import com.mongodb.MongoException;
import com.shopexpress.catalogo.dto.Dtos.ItemSolicitado;
import com.shopexpress.catalogo.error.Errores;
import com.shopexpress.catalogo.modelo.Producto;
import com.shopexpress.catalogo.modelo.Reserva;
import com.shopexpress.catalogo.modelo.Reserva.Estado;
import com.shopexpress.catalogo.modelo.Reserva.ItemReservado;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

/**
 * Inventario: el corazon de la regla "no puede haber venta sin stock".
 *
 * <p>Cada descuento es un update ATOMICO CONDICIONAL sobre el documento del producto:
 * {@code findAndModify({_id, stock: {$gte: cant}}, {$inc: {stock: -cant}})}. MongoDB garantiza
 * atomicidad por documento, asi que dos pedidos concurrentes nunca pueden dejar el stock negativo.
 * Si el pedido tiene varios productos, todo corre dentro de una transaccion multi-documento
 * (replica set, readConcern/writeConcern majority): o se reservan todos o ninguno.
 */
@Service
public class InventarioService {

    private static final Logger log = LoggerFactory.getLogger(InventarioService.class);
    private static final int MAX_INTENTOS = 20;

    private final MongoTemplate mongo;
    private final TransactionTemplate tx;
    private final ProductoService productos;

    public InventarioService(MongoTemplate mongo, PlatformTransactionManager txManager, ProductoService productos) {
        this.mongo = mongo;
        this.tx = new TransactionTemplate(txManager);
        this.productos = productos;
    }

    public record Resultado(Reserva reserva, boolean nueva) {
    }

    /** Paso de la SAGA. Idempotente por pedido_id. */
    public Resultado reservar(String pedidoId, List<ItemSolicitado> items) {
        // Agrupa productos repetidos y ordena por id (orden de acceso determinista).
        Map<String, Integer> pedidosPorProducto = new TreeMap<>();
        items.forEach(i -> pedidosPorProducto.merge(i.productoId(), i.cantidad(), Integer::sum));

        Resultado r = conReintentos(() -> tx.execute(status -> {
            Reserva existente = mongo.findById(pedidoId, Reserva.class);
            if (existente != null) {
                if (existente.estado() == Estado.LIBERADA) {
                    throw Errores.conflicto("La reserva del pedido " + pedidoId + " ya fue liberada");
                }
                return new Resultado(existente, false);
            }
            Instant ahora = Instant.now();
            List<ItemReservado> reservados = new ArrayList<>();
            for (Map.Entry<String, Integer> e : pedidosPorProducto.entrySet()) {
                Producto p = mongo.findAndModify(
                        Query.query(Criteria.where("_id").is(e.getKey()).and("stock").gte(e.getValue())),
                        new Update().inc("stock", -e.getValue()).set("actualizado_en", ahora),
                        FindAndModifyOptions.options().returnNew(true),
                        Producto.class);
                if (p == null) {
                    throw sinStock(e.getKey(), e.getValue()); // aborta la transaccion: rollback de lo ya descontado
                }
                reservados.add(new ItemReservado(p.id(), p.nombre(), e.getValue(), p.precio()));
            }
            Reserva nueva = new Reserva(pedidoId, reservados, Estado.ACTIVA, ahora, ahora);
            mongo.insert(nueva);
            return new Resultado(nueva, true);
        }));
        if (r.nueva()) {
            pedidosPorProducto.keySet().forEach(productos::invalidarCache);
        }
        return r;
    }

    /** Compensacion de la SAGA. Idempotente: liberar algo inexistente o ya liberado no hace nada. */
    public void liberar(String pedidoId) {
        Reserva liberada = conReintentos(() -> tx.execute(status -> {
            Reserva r = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(pedidoId).and("estado").is(Estado.ACTIVA)),
                    new Update().set("estado", Estado.LIBERADA).set("actualizado_en", Instant.now()),
                    FindAndModifyOptions.options().returnNew(true),
                    Reserva.class);
            if (r != null) {
                for (ItemReservado item : r.items()) {
                    mongo.updateFirst(Query.query(Criteria.where("_id").is(item.productoId())),
                            new Update().inc("stock", item.cantidad()).set("actualizado_en", Instant.now()),
                            Producto.class);
                }
            }
            return r;
        }));
        if (liberada != null) {
            liberada.items().forEach(i -> productos.invalidarCache(i.productoId()));
            log.info("Reserva {} liberada (compensacion)", pedidoId);
        }
    }

    public Reserva obtener(String pedidoId) {
        Reserva r = mongo.findById(pedidoId, Reserva.class);
        if (r == null) {
            throw Errores.noEncontrado("No hay reserva para el pedido " + pedidoId);
        }
        return r;
    }

    private ErrorResponseException sinStock(String productoId, int solicitado) {
        Producto actual = mongo.findById(productoId, Producto.class);
        if (actual == null) {
            return Errores.noEncontrado("Producto " + productoId + " no existe");
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Stock insuficiente para " + productoId + ": solicitado " + solicitado + ", disponible " + actual.stock());
        pd.setTitle("Stock insuficiente");
        pd.setProperty("producto_id", productoId);
        pd.setProperty("solicitado", solicitado);
        pd.setProperty("disponible", actual.stock());
        return new ErrorResponseException(HttpStatus.CONFLICT, pd, null);
    }

    /**
     * Dos transacciones que tocan el mismo producto chocan (WriteConflict) y MongoDB marca el error
     * como TransientTransactionError: se reintenta con backoff aleatorio. Nunca se reintentan
     * errores de negocio (sin stock, no encontrado).
     */
    private <T> T conReintentos(Supplier<T> operacion) {
        for (int intento = 1; ; intento++) {
            try {
                return operacion.get();
            } catch (RuntimeException e) {
                if (!esTransitorio(e) || intento >= MAX_INTENTOS) {
                    if (esTransitorio(e)) {
                        throw Errores.error(HttpStatus.SERVICE_UNAVAILABLE, "Alta contencion sobre el inventario, reintente");
                    }
                    throw e;
                }
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * intento));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean esTransitorio(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MongoException me
                    && (me.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || me.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                    || me.getCode() == 112 /* WriteConflict */)) {
                return true;
            }
        }
        return false;
    }
}
