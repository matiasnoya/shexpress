package com.shopexpress.usuarios.modelo;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento de usuario.
 * <ul>
 *   <li>{@code direcciones}: EMBEBIDAS (pocas, se leen siempre con el usuario).</li>
 *   <li>{@code historialCompras}: REFERENCIAS a pedidos (el detalle vive en el servicio de Pedidos).</li>
 * </ul>
 */
@Document("usuarios")
@CompoundIndex(name = "documento_unico", def = "{'tipo_documento': 1, 'nro_documento': 1}", unique = true)
public record Usuario(
        @Id String id,
        String tipoDocumento,
        String nroDocumento,
        String nombre,
        String apellido,
        @Indexed(unique = true) String email,
        List<Direccion> direcciones,
        List<CompraRef> historialCompras,
        Instant creadoEn) {

    public record Direccion(
            @NotBlank String calle,
            @NotBlank String ciudad,
            @NotBlank String codigoPostal) {
    }

    public record CompraRef(String pedidoId, BigDecimal total, Instant fecha) {
    }
}
