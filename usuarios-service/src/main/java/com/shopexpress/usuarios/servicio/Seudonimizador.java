package com.shopexpress.usuarios.servicio;

import com.shopexpress.usuarios.dto.Dtos.UsuarioExportado;
import com.shopexpress.usuarios.modelo.Usuario;
import com.shopexpress.usuarios.modelo.Usuario.CompraRef;
import com.shopexpress.usuarios.modelo.Usuario.Direccion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seudonimizacion para exportar datos (analitica, BI) sin exponer datos personales.
 * <ul>
 *   <li>id y email -> HMAC-SHA256 con sal secreta: permite cruzar exportaciones, no reidentificar.</li>
 *   <li>documento -> enmascarado (solo ultimos 3 digitos).</li>
 *   <li>nombre/apellido -> iniciales; direccion -> solo ciudad y codigo postal (generalizacion).</li>
 * </ul>
 */
@Component
public class Seudonimizador {

    private final SecretKeySpec clave;

    public Seudonimizador(@Value("${shopexpress.export.salt}") String sal) {
        this.clave = new SecretKeySpec(sal.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public UsuarioExportado exportar(Usuario u) {
        List<Direccion> dirs = u.direcciones() == null ? List.of() : u.direcciones();
        List<CompraRef> compras = u.historialCompras() == null ? List.of() : u.historialCompras();
        Direccion principal = dirs.isEmpty() ? null : dirs.getFirst();
        BigDecimal total = compras.stream().map(CompraRef::total).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new UsuarioExportado(
                "anon_" + hmac(u.id()).substring(0, 16),
                hmac(u.email()),
                enmascarar(u.nroDocumento()),
                inicial(u.nombre()) + inicial(u.apellido()),
                principal == null ? null : principal.ciudad(),
                principal == null ? null : principal.codigoPostal(),
                compras.size(),
                total);
    }

    private String hmac(String valor) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(clave);
            return HexFormat.of().formatHex(mac.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enmascarar(String doc) {
        if (doc == null || doc.length() <= 3) {
            return "***";
        }
        return "*".repeat(doc.length() - 3) + doc.substring(doc.length() - 3);
    }

    private static String inicial(String s) {
        return s == null || s.isBlank() ? "" : s.substring(0, 1).toUpperCase() + ".";
    }
}
