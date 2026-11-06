package com.shopexpress.pedidos.saga;

import com.shopexpress.pedidos.cliente.Clientes.CatalogoCliente;
import com.shopexpress.pedidos.cliente.Clientes.UsuariosCliente;
import com.shopexpress.pedidos.modelo.Pedido;
import com.shopexpress.pedidos.modelo.Pedido.Pago.EstadoPago;
import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Resultado;
import com.shopexpress.pedidos.modelo.Pedido.PasoSaga.Tipo;
import com.shopexpress.pedidos.modelo.Pedido.Saga.EstadoSaga;
import com.shopexpress.pedidos.pago.PasarelaPagoSimulada;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Garantiza que la SAGA converja: si una compensacion fallo (p.ej. el catalogo estaba caido),
 * se reintenta periodicamente hasta lograrlo. Todas las compensaciones son idempotentes, por lo
 * que reintentarlas es seguro. El pedido pasa de COMPENSACION_FALLIDA a COMPENSADA.
 */
@Component
public class ReintentadorCompensaciones {

    private static final Logger log = LoggerFactory.getLogger(ReintentadorCompensaciones.class);

    private final MongoTemplate mongo;
    private final CatalogoCliente catalogo;
    private final UsuariosCliente usuarios;
    private final PasarelaPagoSimulada pasarela;

    public ReintentadorCompensaciones(MongoTemplate mongo, CatalogoCliente catalogo, UsuariosCliente usuarios,
                                      PasarelaPagoSimulada pasarela) {
        this.mongo = mongo;
        this.catalogo = catalogo;
        this.usuarios = usuarios;
        this.pasarela = pasarela;
    }

    @Scheduled(fixedDelayString = "${shopexpress.compensaciones.reintento:30s}",
               initialDelayString = "${shopexpress.compensaciones.reintento:30s}")
    public void reintentar() {
        List<Pedido> pendientes = mongo.find(
                Query.query(Criteria.where("saga.estado").is(EstadoSaga.COMPENSACION_FALLIDA)), Pedido.class);
        for (Pedido p : pendientes) {
            List<String> restantes = new ArrayList<>();
            for (String paso : p.getSaga().getCompensacionesPendientes()) {
                try {
                    ejecutar(paso, p);
                    p.registrarPaso(paso, Tipo.COMPENSACION, Resultado.OK, "Compensacion completada en reintento");
                    log.info("Compensacion {} del pedido {} completada en reintento", paso, p.getPedidoId());
                } catch (RuntimeException e) {
                    restantes.add(paso);
                }
            }
            p.getSaga().getCompensacionesPendientes().clear();
            p.getSaga().getCompensacionesPendientes().addAll(restantes);
            if (restantes.isEmpty()) {
                p.getSaga().setEstado(EstadoSaga.COMPENSADA);
            }
            mongo.save(p);
        }
    }

    private void ejecutar(String paso, Pedido p) {
        switch (paso) {
            case "LIBERAR_STOCK" -> catalogo.liberar(p.getPedidoId());
            case "QUITAR_HISTORIAL" -> usuarios.quitarCompra(p.getUsuarioId(), p.getPedidoId());
            case "REEMBOLSAR_PAGO" -> {
                pasarela.reembolsar(p.getPago().getTransaccionId(), p.getTotal());
                p.getPago().setEstadoPago(EstadoPago.REEMBOLSADO);
            }
            default -> throw new IllegalStateException("Compensacion desconocida: " + paso);
        }
    }
}
