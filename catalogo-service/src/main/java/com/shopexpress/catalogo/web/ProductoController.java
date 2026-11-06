package com.shopexpress.catalogo.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.shopexpress.catalogo.dto.Dtos.CrearProductoRequest;
import com.shopexpress.catalogo.modelo.Producto;
import com.shopexpress.catalogo.servicio.ProductoService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Productos", description = "Catalogo de productos")
@RequestMapping("/api/productos")
public class ProductoController {

    private final ProductoService servicio;

    public ProductoController(ProductoService servicio) {
        this.servicio = servicio;
    }

    @Operation(summary = "Crea un producto", description = "La categoria queda embebida en el producto (slug + nombre).")
    @PostMapping
    public ResponseEntity<Producto> crear(@Valid @RequestBody CrearProductoRequest req) {
        Producto p = servicio.crear(req);
        return ResponseEntity.created(URI.create("/api/productos/" + p.id())).body(p);
    }

    /** Informacion del producto y su stock disponible (lectura rapida, consistencia eventual). */
    @Operation(summary = "Obtiene un producto y su stock", description = "Lectura AP: Redis (TTL 30s) y secundario de MongoDB; el stock puede tener segundos de atraso.")
    @GetMapping("/{id}")
    public Producto obtener(@PathVariable String id) {
        return servicio.obtener(id);
    }
}
