# Lector de impactos WiFi

Una cámara ESP32 junto a la diana que marca cada impacto en la app al momento y en orden, sin hacer fotos con el móvil.

El lector vigila la diana. Cuando aparece un agujero nuevo y la imagen se queda quieta (menos de un segundo), guarda esa foto y avisa. La app la descarga, la compara con la anterior para encontrar el agujero nuevo y lo añade al final de la lista. Como sabe qué agujero es nuevo, separa también los impactos pegados o solapados.

## Material

| Pieza | Notas |
|---|---|
| **Seeed XIAO ESP32S3 Sense** | Recomendada: cámara de 2048×1536 (OV3660) o 1600×1200 (OV2640), 8 MB de PSRAM, antena externa incluida y cargador de batería. Monta la antena y el disipador. |
| Alimentación | Lo más sencillo: un powerbank pequeño por USB‑C. Si la quieres dentro de la caja: una LiPo de 3,7 V soldada a los pads BAT de la placa (carga a 100 mA por USB‑C). |
| Chapa de acero de 2–3 mm | Delante y debajo de la cámara, inclinada, para que un tiro bajo rebote hacia el suelo. |
| Fondo detrás de la diana | **Importante**: una cartulina gris medio o de color (naranja, verde…), a 1–3 cm detrás del papel. Ver más abajo. |
| Caja impresa en 3D | Con un hueco para el objetivo y una rótula o bisagra para orientarla. |

También vale una **ESP32-CAM AI-Thinker** (más barata, 1600×1200), con una antena externa si el WiFi no llega bien.

## Grabar el firmware

1. Descarga el binario desde la versión **«Lector de impactos (firmware ESP32)»** en [Releases](https://github.com/Zeux87/puntuador-dianas/releases/tag/lector):
   - `Lector-XIAO-ESP32S3.bin` para la XIAO ESP32S3 Sense
   - `Lector-ESP32-CAM.bin` para la ESP32-CAM AI-Thinker
2. En el ordenador, con Chrome o Edge, abre <https://espressif.github.io/esptool-js/>.
3. Conecta la placa por USB y pulsa **Connect**. Si no aparece, desconéctala, mantén pulsado **BOOT** mientras la conectas y vuelve a probar.
4. En **Flash Address** pon `0x0`, elige el `.bin` y pulsa **Program**.
5. Pulsa **RESET** (o desconecta y conecta). Debe aparecer la red WiFi **LineaDeTiro-Lector**.

También se puede compilar `lector.ino` con Arduino IDE (núcleo ESP32 de Espressif 3.x): placa **XIAO_ESP32S3** con **PSRAM: OPI PSRAM**, o **AI Thinker ESP32-CAM**.

## Montaje

- **Posición**: delante de la diana y por debajo (o a un lado), a unos 25–40 cm, mirándola con un ángulo de 40° o menos. La app corrige la inclinación (admite hasta unos 55°), pero cuanto más de frente, mejor.
- **Encuadre**: la diana entera dentro de la imagen, ocupando entre la mitad y tres cuartos del ancho.
- **Protección**: la cámara nunca en la línea de tiro. La chapa delante tapa todo menos el objetivo.
- **Fondo**: lo que se ve a través del agujero es lo que hay detrás del papel. Si es negro, los agujeros dentro del negro de la diana no se ven; si es blanco, se ven mal los de la zona blanca. Un gris medio o un color se distingue en las dos zonas.
- **Luz**: uniforme y estable sobre la diana. Evita sol directo que vaya cambiando y sombras de gente pasando.

Para apuntar la cámara, únete con el móvil a la red **LineaDeTiro-Lector** (clave `lineadetiro`) y abre <http://192.168.4.1> en el navegador: verás la imagen en directo, un botón para **girarla 180°** si la cámara está boca abajo y la **sensibilidad**. Comprueba que los números de la diana se leen bien y no salen en espejo.

**Enfocar**: la lente viene enfocada para lejos y a 30 cm hay que ajustarla. Con la diana en su sitio y bien iluminada, mira el número **«Nitidez del centro»** de esa página y la vista **Lupa 1:1**. Gira la lente muy poco cada vez (1/16 de vuelta), espera un par de fotos y quédate donde el número sea más alto. Si baja, gira hacia el otro lado. Quita antes el plástico protector de la lente si lo trae.

## Uso con la app

1. Enciende el lector y espera a que se apague su luz (unos segundos). Si se queda parpadeando, no encuentra la cámara o le falta la PSRAM.
2. En la app crea una sesión y elige **«Con el lector WiFi»** (o, sin sesión, pulsa **Lector WiFi** en la pantalla de puntuar).
3. La primera vez Android pregunta si quieres unirte a **LineaDeTiro-Lector**: acepta. La app usa esa red solo para el lector; el resto del móvil sigue con sus datos. Mientras está conectado, la pantalla no se apaga.
4. Dispara. Cada impacto aparece en menos de un segundo con su número de orden y su puntuación. Los que no se ven claros salen en naranja para que los revises.
5. Al cambiar la diana el lector lo nota. Si la anterior tiene impactos sin guardar, la nueva espera hasta que pulses **Guardar diana**.

**Releer diana** vuelve a leer la diana actual desde cero (útil si se ha movido el portablancos). **Desconectar** suelta la red del lector.

## Límites

- Un perdigón que pasa exactamente por un agujero ya hecho no cambia la imagen: añádelo a mano con «Añadir».
- Algo que se mueve delante de la cámara (una mano cambiando la diana) se toma como diana nueva cuando la imagen vuelve a quedarse quieta.
- La placa se calienta con la cámara siempre encendida: déjale el disipador y ventilación en la caja.

## Cómo funciona (para tocar el código)

- El firmware (`lector.ino`) toma unas 5 fotos por segundo a máxima resolución. Cada una se decodifica a 1/8 en gris y se compara con una referencia (corrigiendo el brillo global). Si cambian pocos píxeles y la imagen se queda quieta 2 fotos seguidas, es un **impacto**; si cambian muchos, es una **diana nueva** («cambio»).
- Rutas: `/estado` (JSON con el último aviso), `/foto?seq=N` (foto exacta del aviso), `/referencia`, `/ajustes?sens=&giro=&luz=`.
- La app (`MainActivity.java` y `Lector.java`) se une a la red con `WifiNetworkSpecifier`, pregunta por `/estado` cada 350 ms y pasa cada foto nueva a la página. En `index.html`, `lectorLocaliza()` alinea las dos fotos (también por debajo del píxel), resta, y busca el disco del tamaño del perdigón que mejor explica la diferencia, cerca de la posición aproximada que manda el lector.
- `herramientas/banco-lector.js` simula la cámara y dispara 80 impactos (con dobles, ruido, JPEG y papel que se mueve) para medir la precisión: con fondo gris, error medio de 0,23 mm.
