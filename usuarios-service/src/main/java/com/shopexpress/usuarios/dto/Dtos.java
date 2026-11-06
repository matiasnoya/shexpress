package com.shopexpress.usuarios.dto;

import com.shopexpress.usuarios.modelo.Usuario.Direccion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record CrearUsuarioRequest(
            @NotBlank @Pattern(regexp = "DNI|CI|PASAPORTE", message = "debe ser DNI, CI o PASAPORTE") String tipoDocumento,
            @NotBlank String nroDocumento,
            @NotBlank String nombre,
            @NotBlank String apellido,
            @NotBlank @Email String email,
            @NotEmpty List<@Valid Direccion> direcciones) {
    }

    /** Endpoint interno usado por la SAGA de Pedidos. */
    public record RegistrarCompraRequest(@NotBlank String pedidoId, @NotNull @PositiveOrZero BigDecimal total) {
    }

    /** Vista seudonimizada: no permite reidentificar a la persona sin la sal secreta. */
    public record UsuarioExportado(
            String usuarioRef,
            String emailHash,
            String documentoEnmascarado,
            String iniciales,
            String ciudad,
            String codigoPostal,
            int cantidadCompras,
            BigDecimal totalComprado) {
    }
}
