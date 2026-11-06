# ShopExpress — Resolución de puntos críticos

Registro de decisiones tomadas **antes** de implementar. Cada punto tiene: problema, decisión, cómo se implementa y cómo se demuestra.

Stack fijado: **Java 21 · Spring Boot 4.1.2 · Maven · MongoDB 8 · Redis 7 · Docker Compose**.

---

## PC-1. Entorno: Docker no instalado

**Estado actual (6/11):** WSL no instalado, Docker Desktop no instalado. Virtualización por hardware: OK. RAM: 15,7 GB (suficiente para 5 contenedores).

**Decisión:** Docker Desktop sobre WSL2.

**Pasos (requieren administrador y un reinicio — los hace el usuario):**
```powershell
wsl --install            # instala WSL2 + Ubuntu; reiniciar al terminar
winget install -e --id Docker.DockerDesktop
```
Luego abrir Docker Desktop, aceptar términos y verificar:
```powershell
docker version
docker compose version
docker run --rm hello-world
```
**Plan B** si Docker falla el día de la defensa: los servicios corren con `mvn spring-boot:run` contra Mongo/Redis en otra máquina del grupo. Igual se entrega el `docker-compose.yml`.

---

## PC-2. MongoDB necesita replica set

**Problema:** las transacciones multi-documento y `readConcern: majority` exigen replica set; un `mongod` suelto no las soporta.

**Decisión:** replica set de **3 nodos** en Compose (`mongo1` primario preferido, `mongo2`, `mongo3`). Así la demo de CAP es real: lecturas desde secundarios (consistencia eventual) y escrituras `w: majority` (consistencia fuerte). Si la RAM no alcanza, se baja a 1 nodo (`rs0`) sin cambiar el código.

**Implementación:**
```yaml
mongo1:
  image: mongo:8.0
  command: ["--replSet", "rs0", "--bind_ip_all"]
  healthcheck:
    test: ["CMD", "mongosh", "--quiet", "--eval",
           "try{rs.status().ok}catch(e){rs.initiate({_id:'rs0',members:[{_id:0,host:'mongo1:27017',priority:2},{_id:1,host:'mongo2:27017'},{_id:2,host:'mongo3:27017'}]}).ok}"]
    interval: 5s
    retries: 30
```
El healthcheck inicializa el replica set de forma idempotente; los servicios usan `depends_on: { mongo1: { condition: service_healthy } }`.

URI: `mongodb://mongo1:27017,mongo2:27017,mongo3:27017/<db>?replicaSet=rs0`

---

## PC-3. Sin sobreventa (stock)

**Problema:** dos pedidos simultáneos leen `stock=1` y ambos venden.

**Decisión:** el descuento de stock es **una sola operación atómica condicional** en el documento del producto. MongoDB garantiza atomicidad a nivel documento, por lo que no hace falta lock distribuido.

```java
// InventarioService.reservar(productoId, cantidad, pedidoId)
Query q = Query.query(Criteria.where("_id").is(productoId)
                              .and("stock").gte(cantidad));
Update u = new Update().inc("stock", -cantidad);
UpdateResult r = mongoTemplate.updateFirst(q, u, Producto.class);
if (r.getModifiedCount() == 0) throw new StockInsuficienteException(productoId);
```
- Pedidos con varios ítems: se reserva ítem por ítem **dentro de una transacción Mongo** (`@Transactional` + `MongoTransactionManager`): si falla un ítem, se revierten los anteriores.
- Se registra la reserva en la colección `reservas` (`{pedidoId, items, estado: ACTIVA|LIBERADA}`) con **índice único en `pedidoId`** → reservar dos veces el mismo pedido es imposible y liberar es idempotente.
- Regla: **la caché de Redis nunca se consulta para decidir stock**; solo se invalida (`DEL producto:{id}`) después de reservar/liberar.

**Demostración:** producto con `stock=1`, 20 `POST /api/pedidos` en paralelo → exactamente 1 `CONFIRMADO`, 19 `RECHAZADO`, stock final `0`.

---

## PC-4. Pedido único / idempotencia

**Problema:** reintentos del cliente (timeout, doble clic) no deben crear dos pedidos ni cobrar dos veces.

**Decisión (doble barrera):**
1. Header obligatorio `Idempotency-Key` en `POST /api/pedidos`.
2. Redis: `SET idem:{key} <pedidoId> NX EX 86400`.
   - Si la clave ya existe → se devuelve el pedido existente (`200` con el mismo cuerpo), sin reejecutar la SAGA.
3. Mongo: índice **único** en `pedidos.idempotencyKey` y `pedidos.pedidoId` (garantía final aunque Redis se pierda).

El `pedido_id` lo genera el servidor (`ord_` + UUID corto); el cliente nunca lo envía.

---

## PC-5. Justificación CAP (consistencia vs. disponibilidad)

| Operación | Prioridad | Mecanismo |
|---|---|---|
| `GET /api/productos/{id}`, `GET /api/usuarios/{id}` | **AP** – rapidez y disponibilidad, consistencia eventual | Redis cache-aside (TTL 60 s usuarios / 30 s productos); en cache miss, Mongo con `readPreference=secondaryPreferred` |
| `POST /api/pedidos`, reserva de stock, pago | **CP** – consistencia sobre velocidad | Mongo `writeConcern=majority`, `readConcern=majority`, lecturas en `primary`, update atómico condicional, transacciones |

Configuración por servicio:
- usuarios / catálogo (lecturas): `MongoTemplate` de lectura con `ReadPreference.secondaryPreferred()`.
- pedidos y endpoints de inventario (escrituras): `WriteConcern.MAJORITY`, `ReadConcern.MAJORITY`, `ReadPreference.primary()`.

Frase para la defensa: *"El GET del producto puede mostrar un stock con hasta 30 s de atraso; eso es aceptable porque la decisión de venta nunca usa ese dato, la toma el update atómico en el primario."*

---

## PC-6. Rol real de Redis

Redis **no es decorativo**; tiene tres responsabilidades asignadas:

| Servicio | Uso de Redis | Claves |
|---|---|---|
| Usuarios | Read store / caché de perfiles | `usuario:{id}` (JSON, TTL 60 s) |
| Catálogo | Read store / caché de productos | `producto:{id}` (JSON, TTL 30 s) |
| Pedidos | Idempotencia de pedidos | `idem:{key}` (TTL 24 h) |

Justificación: Redis es in-memory, latencia < 1 ms, escala horizontal con réplicas; ideal para el lado de lectura (AP). MongoDB es la fuente de verdad (documentos flexibles, transacciones, replicación).

**Asignación de bases (database-per-service, aisladas por base lógica):**
- usuarios-service → Mongo `usuarios_db` + Redis (db 0)
- catalogo-service → Mongo `catalogo_db` + Redis (db 1)
- pedidos-service → Mongo `pedidos_db` + Redis (db 2)

Ningún servicio accede a la base de otro; solo se comunican por REST.

---

## PC-7. Endpoints internos para la SAGA

Además de los endpoints del enunciado:

| Servicio | Endpoint interno | Uso en SAGA | Idempotencia |
|---|---|---|---|
| Catálogo | `POST /api/inventario/reservas` body `{pedidoId, items[]}` | Paso: reservar stock | índice único `pedidoId` |
| Catálogo | `DELETE /api/inventario/reservas/{pedidoId}` | Compensación: liberar stock | si ya está `LIBERADA` → 204 sin efecto |
| Usuarios | `POST /api/usuarios/{id}/historial` body `{pedidoId, total, fecha}` | Paso: registrar compra | `$addToSet` |
| Usuarios | `DELETE /api/usuarios/{id}/historial/{pedidoId}` | Compensación | `$pull` |
| Pedidos | `GET /api/pedidos/{id}` | Ver estado y pasos de SAGA en la demo | — |

Se prefijan con `/api/inventario` para separarlos conceptualmente del CRUD de productos; se documentan en el README como "internos".

---

## PC-8. Pago simulado con fallo provocable

**Decisión:** `ProcesadorDePago` usa una pasarela simulada (`PasarelaPagoSimulada`) dentro de pedidos-service con reglas deterministas:

| Condición | Resultado |
|---|---|
| `pago.metodo = "TARJETA_RECHAZADA"` | `RECHAZADO` → compensación |
| `total > 10000` | `RECHAZADO` (límite de crédito) |
| `pago.metodo = "TARJETA_TIMEOUT"` | excepción de timeout → compensación |
| resto (`TARJETA_CREDITO`, `TARJETA_DEBITO`, `TRANSFERENCIA`) | `APROBADO`, `transaccion_id = tx_<uuid>` |

Reembolso (compensación del pago): marca `pago.estado_pago = REEMBOLSADO`.

Para demostrar fallo del último paso (historial de usuario) existe además la variable `SIMULAR_FALLO_HISTORIAL=true` en Compose.

---

## PC-9. El total lo calcula el servidor

El body de `POST /api/pedidos` **no** acepta precios:
```json
{
  "usuario_id": "usr_12345",
  "items": [{ "producto_id": "prod_001", "cantidad": 2 }],
  "pago": { "metodo": "TARJETA_CREDITO" }
}
```
`CalculadorDeTotal` toma `precio_unitario` de la respuesta de la reserva (catálogo devuelve el precio vigente al reservar, leído del primario) y lo guarda como **snapshot embebido** en el pedido, igual que el JSON del anexo.

---

## PC-10. Errata del enunciado

"Servicio de Reservas y Pagos" (en la Guía de Entrega) = **Servicio de Pedidos y Pagos**. En el README se usa siempre "Pedidos y Pagos".

---

## PC-11. Organización del grupo y repositorio

- Un único repo Git (GitHub), rama `main` protegida, una rama por feature (`feat/usuarios`, `feat/catalogo`, `feat/pedidos`).
- Reparto sugerido: 1 persona por servicio + 1 persona para docs/diagramas/Postman/compose (rotativo).
- Contrato entre servicios: los JSON de este documento (PC-7 y PC-9) se congelan el **viernes 7** para que cada uno avance en paralelo.
- `.gitignore` de Maven/IDE; tag `v1.0` el martes 11.

**Pendiente del usuario:** crear el repo remoto en GitHub, invitar al grupo y configurar `git config user.name`.

---

## Resumen de modelado (embebido vs. referencia)

| Relación | Patrón | Motivo |
|---|---|---|
| Usuario → direcciones | **Embebido** | Pocas, se leen siempre con el usuario |
| Usuario → historial de compras | **Referencia** (`pedidoId`, total, fecha) | Crece sin límite; el detalle vive en Pedidos |
| Producto → categoría | **Embebido** (`{id, nombre}`) | Cambia poco, evita joins en la lectura rápida |
| Pedido → items (precio) | **Embebido** (snapshot) | El precio histórico no debe cambiar con el catálogo |
| Pedido → pago | **Embebido** | Ciclo de vida 1:1 con el pedido |
| Pedido → usuario / producto | **Referencia** (ids) | Pertenecen a otros microservicios |
