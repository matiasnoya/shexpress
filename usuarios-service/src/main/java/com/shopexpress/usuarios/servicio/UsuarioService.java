package com.shopexpress.usuarios.servicio;

import com.shopexpress.usuarios.cache.CacheLectura;
import com.shopexpress.usuarios.config.MongoConfig;
import com.shopexpress.usuarios.dto.Dtos.CrearUsuarioRequest;
import com.shopexpress.usuarios.dto.Dtos.RegistrarCompraRequest;
import com.shopexpress.usuarios.error.Errores;
import com.shopexpress.usuarios.modelo.Usuario;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class UsuarioService {

    private final MongoTemplate escritura;
    private final MongoTemplate lectura;
    private final CacheLectura cache;
    private final Duration ttl;

    public UsuarioService(MongoTemplate escritura,
                          @Qualifier(MongoConfig.LECTURA) MongoTemplate lectura,
                          CacheLectura cache,
                          @Value("${shopexpress.cache.ttl-usuario:60s}") Duration ttl) {
        this.escritura = escritura;
        this.lectura = lectura;
        this.cache = cache;
        this.ttl = ttl;
    }

    public Usuario crear(CrearUsuarioRequest req) {
        Usuario usuario = new Usuario(
                "usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                req.tipoDocumento(),
                req.nroDocumento().trim(),
                req.nombre().trim(),
                req.apellido().trim(),
                req.email().trim().toLowerCase(),
                req.direcciones(),
                List.of(),
                Instant.now());
        try {
            escritura.insert(usuario);
        } catch (DuplicateKeyException e) {
            throw Errores.conflicto("Ya existe un usuario con ese email o documento");
        }
        // write-through: la lectura inmediata posterior no depende del atraso de los secundarios
        cache.guardar(clave(usuario.id()), usuario, ttl);
        return usuario;
    }

    /**
     * Lectura AP: Redis -> secundario de Mongo. Solo si el secundario no lo tiene
     * (posible atraso de replicacion) se confirma contra el primario antes de responder 404.
     */
    public Usuario obtener(String id) {
        return cache.obtener(clave(id), Usuario.class).orElseGet(() -> {
            Usuario u = lectura.findById(id, Usuario.class);
            if (u == null) {
                u = escritura.findById(id, Usuario.class);
            }
            if (u == null) {
                throw Errores.noEncontrado("Usuario " + id + " no existe");
            }
            cache.guardar(clave(id), u, ttl);
            return u;
        });
    }

    /** Paso de la SAGA. Idempotente: si el pedido ya esta en el historial no lo duplica. */
    public void registrarCompra(String id, RegistrarCompraRequest req) {
        Query sinEsePedido = Query.query(Criteria.where("_id").is(id)
                .and("historial_compras.pedido_id").ne(req.pedidoId()));
        Update push = new Update().push("historial_compras",
                new Document("pedido_id", req.pedidoId())
                        .append("total", new Decimal128(req.total()))
                        .append("fecha", Date.from(Instant.now())));
        if (escritura.updateFirst(sinEsePedido, push, Usuario.class).getMatchedCount() == 0
                && !escritura.exists(Query.query(Criteria.where("_id").is(id)), Usuario.class)) {
            throw Errores.noEncontrado("Usuario " + id + " no existe");
        }
        cache.invalidar(clave(id));
    }

    /** Compensacion de la SAGA. Idempotente: quitar algo que no esta no es un error. */
    public void quitarCompra(String id, String pedidoId) {
        escritura.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().pull("historial_compras", new Document("pedido_id", pedidoId)),
                Usuario.class);
        cache.invalidar(clave(id));
    }

    /** Lectura masiva para exportar: va a secundarios para no cargar al primario. */
    public Stream<Usuario> todos() {
        return lectura.stream(new Query(), Usuario.class);
    }

    private static String clave(String id) {
        return "usuario:" + id;
    }
}
