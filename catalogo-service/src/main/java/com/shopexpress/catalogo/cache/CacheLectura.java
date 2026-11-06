package com.shopexpress.catalogo.cache;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Cache-aside sobre Redis para el lado de lectura (AP).
 * Es "fail-soft": si Redis no responde se registra un warning y se sigue contra MongoDB,
 * priorizando la disponibilidad del servicio.
 */
@Component
public class CacheLectura {

    private static final Logger log = LoggerFactory.getLogger(CacheLectura.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public CacheLectura(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public <T> Optional<T> obtener(String clave, Class<T> tipo) {
        try {
            String valor = redis.opsForValue().get(clave);
            return valor == null ? Optional.empty() : Optional.of(json.readValue(valor, tipo));
        } catch (RuntimeException e) {
            log.warn("Cache no disponible al leer {}: {}", clave, e.getMessage());
            return Optional.empty();
        }
    }

    public void guardar(String clave, Object valor, Duration ttl) {
        try {
            redis.opsForValue().set(clave, json.writeValueAsString(valor), ttl);
        } catch (RuntimeException e) {
            log.warn("Cache no disponible al guardar {}: {}", clave, e.getMessage());
        }
    }

    public void invalidar(String clave) {
        try {
            redis.delete(clave);
        } catch (RuntimeException e) {
            log.warn("Cache no disponible al invalidar {}: {}", clave, e.getMessage());
        }
    }
}
