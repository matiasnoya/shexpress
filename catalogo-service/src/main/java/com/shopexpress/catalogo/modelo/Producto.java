package com.shopexpress.catalogo.modelo;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Producto del catalogo. La categoria va EMBEBIDA (cambia poco y evita un "join" en la lectura rapida).
 * El {@code stock} vive en el mismo documento para que reservar sea una operacion atomica de MongoDB.
 */
@Document("productos")
public record Producto(
        @Id String id,
        String nombre,
        String descripcion,
        Categoria categoria,
        BigDecimal precio,
        int stock,
        Instant creadoEn,
        Instant actualizadoEn) {

    public record Categoria(@Indexed String slug, String nombre) {
    }
}
