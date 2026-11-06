package com.shopexpress.catalogo.web;

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
@RequestMapping("/api/productos")
public class ProductoController {

    private final ProductoService servicio;

    public ProductoController(ProductoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<Producto> crear(@Valid @RequestBody CrearProductoRequest req) {
        Producto p = servicio.crear(req);
        return ResponseEntity.created(URI.create("/api/productos/" + p.id())).body(p);
    }

    /** Informacion del producto y su stock disponible (lectura rapida, consistencia eventual). */
    @GetMapping("/{id}")
    public Producto obtener(@PathVariable String id) {
        return servicio.obtener(id);
    }
}
