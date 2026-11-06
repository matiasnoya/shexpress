package com.shopexpress.catalogo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record CrearProductoRequest(
            @NotBlank String nombre,
            String descripcion,
            @NotBlank String categoria,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal precio,
            @NotNull @PositiveOrZero Integer stock) {
    }

    /** Endpoint interno usado por la SAGA de Pedidos. */
    public record ReservarRequest(@NotBlank String pedidoId, @NotEmpty List<@Valid ItemSolicitado> items) {
    }

    public record ItemSolicitado(@NotBlank String productoId, @NotNull @Positive Integer cantidad) {
    }
}
