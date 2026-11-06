package com.shopexpress.pedidos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.shopexpress.pedidos.dto.Dtos.CrearPedidoRequest;
import com.shopexpress.pedidos.error.Errores;
import com.shopexpress.pedidos.modelo.Pedido;
import com.shopexpress.pedidos.modelo.Pedido.Estado;
import com.shopexpress.pedidos.saga.OrquestadorSagaPedido;
import com.shopexpress.pedidos.saga.OrquestadorSagaPedido.ResultadoSaga;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Pedidos", description = "SAGA orquestada + Chain of Responsibility")
@RequestMapping("/api/pedidos")
public class PedidoController {

    private final OrquestadorSagaPedido orquestador;

    public PedidoController(OrquestadorSagaPedido orquestador) {
        this.orquestador = orquestador;
    }

    /**
     * Inicia la SAGA y la Chain of Responsibility.
     * <ul>
     *   <li>201: pedido CONFIRMADO.</li>
     *   <li>422: pedido RECHAZADO (body con codigo_rechazo, motivo y log de la SAGA).</li>
     *   <li>200 + {@code Idempotent-Replay: true}: la Idempotency-Key ya se habia usado; se devuelve
     *       el pedido original sin volver a cobrar ni reservar.</li>
     * </ul>
     */
    @Operation(summary = "Crea un pedido (inicia la SAGA)", description = "201 CONFIRMADO, 422 RECHAZADO (ver codigo_rechazo y saga.pasos), 200 si la Idempotency-Key ya se uso. Metodos de pago: TARJETA_CREDITO, TARJETA_DEBITO, TRANSFERENCIA; para probar fallos: TARJETA_RECHAZADA, TARJETA_TIMEOUT o header X-Simular-Fallo: HISTORIAL.")
    @PostMapping
    public ResponseEntity<Pedido> crear(@Parameter(required = true, description = "Clave unica por intento de compra; repetirla devuelve el mismo pedido", example = "compra-123")
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @Parameter(description = "HISTORIAL fuerza el fallo del ultimo paso para ver las compensaciones")
                                        @RequestHeader(value = "X-Simular-Fallo", required = false) String simularFallo,
                                        @RequestBody CrearPedidoRequest req) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw Errores.error(HttpStatus.BAD_REQUEST, "Header Idempotency-Key obligatorio (1 a 100 caracteres)");
        }
        ResultadoSaga r = orquestador.crearPedido(idempotencyKey.trim(), req, "HISTORIAL".equalsIgnoreCase(simularFallo));
        Pedido p = r.pedido();
        if (r.repetido()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(p);
        }
        if (p.getEstado() == Estado.CONFIRMADO) {
            return ResponseEntity.created(URI.create("/api/pedidos/" + p.getPedidoId())).body(p);
        }
        return ResponseEntity.unprocessableContent().body(p);
    }

    @Operation(summary = "Obtiene un pedido con el log de su SAGA", description = "Lectura en el primario (consistencia fuerte).")
    @GetMapping("/{id}")
    public Pedido obtener(@PathVariable String id) {
        return orquestador.obtener(id);
    }
}
