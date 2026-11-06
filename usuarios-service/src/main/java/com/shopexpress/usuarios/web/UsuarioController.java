package com.shopexpress.usuarios.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.shopexpress.usuarios.dto.Dtos.CrearUsuarioRequest;
import com.shopexpress.usuarios.dto.Dtos.RegistrarCompraRequest;
import com.shopexpress.usuarios.dto.Dtos.UsuarioExportado;
import com.shopexpress.usuarios.error.Errores;
import com.shopexpress.usuarios.modelo.Usuario;
import com.shopexpress.usuarios.servicio.Seudonimizador;
import com.shopexpress.usuarios.servicio.UsuarioService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Usuarios", description = "Perfiles de clientes")
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final UsuarioService servicio;
    private final Seudonimizador seudonimizador;

    public UsuarioController(UsuarioService servicio, Seudonimizador seudonimizador) {
        this.servicio = servicio;
        this.seudonimizador = seudonimizador;
    }

    @Operation(summary = "Crea un usuario", description = "201 creado, 409 si el email o documento ya existen, 400 con detalle por campo.")
    @PostMapping
    public ResponseEntity<Usuario> crear(@Valid @RequestBody CrearUsuarioRequest req) {
        Usuario u = servicio.crear(req);
        return ResponseEntity.created(URI.create("/api/usuarios/" + u.id())).body(u);
    }

    /** Opcional del enunciado: exportacion con datos seudonimizados en JSON o CSV. */
    @Operation(summary = "Exporta usuarios seudonimizados (JSON o CSV)", description = "Opcional del enunciado: ids/email con HMAC-SHA256, documento enmascarado, nombre en iniciales.")
    @GetMapping("/exportar")
    public ResponseEntity<?> exportar(@RequestParam(defaultValue = "json") String formato) {
        List<UsuarioExportado> filas;
        try (Stream<Usuario> usuarios = servicio.todos()) {
            filas = usuarios.map(seudonimizador::exportar).toList();
        }
        return switch (formato.toLowerCase()) {
            case "json" -> ResponseEntity.ok(filas);
            case "csv" -> ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=usuarios_anonimizados.csv")
                    .body(aCsv(filas));
            default -> throw Errores.error(HttpStatus.BAD_REQUEST, "formato debe ser json o csv");
        };
    }

    @Operation(summary = "Obtiene un usuario", description = "Lectura AP: Redis (cache-aside, TTL 60s) y luego secundario de MongoDB.")
    @GetMapping("/{id}")
    public Usuario obtener(@PathVariable String id) {
        return servicio.obtener(id);
    }

    // ---------- Endpoints internos (SAGA de Pedidos) ----------

    @Operation(summary = "[Interno SAGA] Agrega un pedido al historial", description = "Idempotente: si el pedido ya esta no se duplica.")
    @PostMapping("/{id}/historial")
    public ResponseEntity<Void> registrarCompra(@PathVariable String id, @Valid @RequestBody RegistrarCompraRequest req) {
        servicio.registrarCompra(id, req);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "[Interno SAGA] Compensacion: quita un pedido del historial", description = "Idempotente.")
    @DeleteMapping("/{id}/historial/{pedidoId}")
    public ResponseEntity<Void> quitarCompra(@PathVariable String id, @PathVariable String pedidoId) {
        servicio.quitarCompra(id, pedidoId);
        return ResponseEntity.noContent().build();
    }

    private static String aCsv(List<UsuarioExportado> filas) {
        String cabecera = "usuario_ref,email_hash,documento_enmascarado,iniciales,ciudad,codigo_postal,cantidad_compras,total_comprado";
        return Stream.concat(Stream.of(cabecera), filas.stream().map(f -> String.join(",",
                        f.usuarioRef(), f.emailHash(), f.documentoEnmascarado(), csv(f.iniciales()),
                        csv(f.ciudad()), csv(f.codigoPostal()), String.valueOf(f.cantidadCompras()),
                        (f.totalComprado() == null ? BigDecimal.ZERO : f.totalComprado()).toPlainString())))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
