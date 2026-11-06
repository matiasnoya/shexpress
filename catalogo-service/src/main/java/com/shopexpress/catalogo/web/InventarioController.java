package com.shopexpress.catalogo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.shopexpress.catalogo.dto.Dtos.ReservarRequest;
import com.shopexpress.catalogo.modelo.Reserva;
import com.shopexpress.catalogo.servicio.InventarioService;
import com.shopexpress.catalogo.servicio.InventarioService.Resultado;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints internos que usa la SAGA del servicio de Pedidos. */
@RestController
@Tag(name = "Inventario (interno SAGA)", description = "Reservas de stock usadas por el orquestador de Pedidos")
@RequestMapping("/api/inventario/reservas")
public class InventarioController {

    private final InventarioService servicio;

    public InventarioController(InventarioService servicio) {
        this.servicio = servicio;
    }

    /** 201 si reservo ahora, 200 si la reserva ya existia (reintento idempotente), 409 si no hay stock. */
    @Operation(summary = "Reserva stock para un pedido", description = "Update atomico condicional en transaccion: 201 reservado, 200 si ya existia (idempotente), 409 sin stock, 404 producto inexistente.")
    @PostMapping
    public ResponseEntity<Reserva> reservar(@Valid @RequestBody ReservarRequest req) {
        Resultado r = servicio.reservar(req.pedidoId(), req.items());
        return r.nueva()
                ? ResponseEntity.created(URI.create("/api/inventario/reservas/" + req.pedidoId())).body(r.reserva())
                : ResponseEntity.ok(r.reserva());
    }

    @Operation(summary = "Consulta la reserva de un pedido", description = "")
    @GetMapping("/{pedidoId}")
    public Reserva obtener(@PathVariable String pedidoId) {
        return servicio.obtener(pedidoId);
    }

    /** Compensacion: devuelve el stock. Siempre 204 (idempotente). */
    @Operation(summary = "Compensacion: libera la reserva y devuelve el stock", description = "Idempotente: siempre 204.")
    @DeleteMapping("/{pedidoId}")
    public ResponseEntity<Void> liberar(@PathVariable String pedidoId) {
        servicio.liberar(pedidoId);
        return ResponseEntity.noContent().build();
    }
}
