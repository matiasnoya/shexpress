#!/usr/bin/env bash
# Prueba anti-sobreventa: N reservas concurrentes contra un producto con stock S.
# Uso: ./scripts/prueba-concurrencia.sh [N=20] [S=1] [URL_CATALOGO=http://localhost:8082]
N=${1:-20}; S=${2:-1}; URL=${3:-http://localhost:8082}
J='Content-Type: application/json'
PROD=$(curl -s -X POST "$URL/api/productos" -H "$J" \
  -d "{\"nombre\":\"Edicion limitada\",\"categoria\":\"Coleccionables\",\"precio\":99.90,\"stock\":$S}" \
  | grep -o '"id":"prod_[a-z0-9]*"' | cut -d'"' -f4)
echo "Producto $PROD con stock=$S; lanzando $N reservas en paralelo..."
TMP=$(mktemp -d)
for i in $(seq 1 "$N"); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$URL/api/inventario/reservas" -H "$J" \
    -d "{\"pedido_id\":\"ord_conc_${PROD}_$i\",\"items\":[{\"producto_id\":\"$PROD\",\"cantidad\":1}]}" > "$TMP/$i" &
done
wait
OK=$(cat "$TMP"/* | grep -c '^201$'); SIN=$(cat "$TMP"/* | grep -c '^409$'); OTROS=$(cat "$TMP"/* | grep -vcE '^(201|409)$')
sleep 1
FINAL=$(curl -s "$URL/api/productos/$PROD" | grep -o '"stock":-\?[0-9]*' | cut -d: -f2)
echo "Reservas OK (201): $OK | Sin stock (409): $SIN | Otros: $OTROS | Stock final: $FINAL"
if [ "$OK" -eq "$S" ] && [ "$FINAL" -eq 0 ]; then echo "RESULTADO: SIN SOBREVENTA"; else echo "RESULTADO: REVISAR"; exit 1; fi
rm -rf "$TMP"
