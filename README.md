# Puntuador de dianas

App Android que calcula la puntuación de una diana de **pistola aire 10 m** o **carabina aire 10 m** a partir de una foto. Usa la regla ISSF del perdigón de 4,5 mm: cuenta el anillo más alto que toque el impacto.

## Descargar la app

Cada vez que se sube un cambio a `main`, GitHub compila el APK solo. Lo encontrarás en la sección **Releases** de este repositorio. Descarga el `.apk` desde el móvil e instálalo; si Android lo pide, permite instalar apps de ese origen.

## Qué hace

- Hace la foto con la cámara o la coge de la galería.
- Localiza el negro de la diana y detecta los impactos.
- Calcula total, decimal, dieces interiores, punto medio de impactos y agrupación.
- Deja mover, añadir y eliminar impactos a mano, y calibrar la diana tocando el centro y el borde del negro.
- Funciona sin conexión.

## Estructura

- `app/src/main/assets/index.html`: toda la lógica de detección y la interfaz.
- `app/src/main/java/es/pablo/puntuador/MainActivity.java`: la ventana Android que muestra la página y abre la cámara o la galería.
- `.github/workflows/compilar-apk.yml`: compila y publica el APK.

La clave de firma (`app/puntuador.jks`) está en el repositorio para que cada versión se pueda instalar encima de la anterior. Mantén el repositorio privado.
