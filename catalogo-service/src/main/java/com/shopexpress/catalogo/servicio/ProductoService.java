package com.shopexpress.catalogo.servicio;

import com.shopexpress.catalogo.cache.CacheLectura;
import com.shopexpress.catalogo.config.MongoConfig;
import com.shopexpress.catalogo.dto.Dtos.CrearProductoRequest;
import com.shopexpress.catalogo.error.Errores;
import com.shopexpress.catalogo.modelo.Producto;
import com.shopexpress.catalogo.modelo.Producto.Categoria;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductoService {

    private final MongoTemplate escritura;
    private final MongoTemplate lectura;
    private final CacheLectura cache;
    private final Duration ttl;

    public ProductoService(MongoTemplate escritura,
                           @Qualifier(MongoConfig.LECTURA) MongoTemplate lectura,
                           CacheLectura cache,
                           @Value("${shopexpress.cache.ttl-producto:30s}") Duration ttl) {
        this.escritura = escritura;
        this.lectura = lectura;
        this.cache = cache;
        this.ttl = ttl;
    }

    public Producto crear(CrearProductoRequest req) {
        Instant ahora = Instant.now();
        Producto p = new Producto(
                "prod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                req.nombre().trim(),
                req.descripcion(),
                new Categoria(slug(req.categoria()), req.categoria().trim()),
                req.precio(),
                req.stock(),
                ahora,
                ahora);
        escritura.insert(p);
        cache.guardar(clave(p.id()), p, ttl);
        return p;
    }

    /**
     * Lectura AP: Redis -> secundario. El stock devuelto puede tener algunos segundos de atraso;
     * es aceptable porque la decision de venta NUNCA usa este dato (la toma la reserva atomica).
     */
    public Producto obtener(String id) {
        return cache.obtener(clave(id), Producto.class).orElseGet(() -> {
            Producto p = lectura.findById(id, Producto.class);
            if (p == null) {
                p = escritura.findById(id, Producto.class);
            }
            if (p == null) {
                throw Errores.noEncontrado("Producto " + id + " no existe");
            }
            cache.guardar(clave(id), p, ttl);
            return p;
        });
    }

    public void invalidarCache(String id) {
        cache.invalidar(clave(id));
    }

    static String clave(String id) {
        return "producto:" + id;
    }

    private static String slug(String texto) {
        return Normalizer.normalize(texto.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
