# Contexto de canciones y documentos

El bot entrega a Gemini la playlist actual, el registro de tareas de este proceso,
las elecciones pendientes de notas y hasta 16 mensajes recientes con rol usuario/bot
(máximo 2500 caracteres por mensaje, separados por número, caducidad de 30 minutos).
Solo se registran respuestas salientes cuyo envío no produjo un error.
Los mensajes, estados, copias identificadas, última selección y preguntas de notas
se guardan en `BOT_CONTEXT_PATH` (por defecto `data/conversation-context.json`).
Se necesita un volumen persistente en esa ruta para sobrevivir a redespliegues.
El archivo contiene historial privado: no debe publicarse ni compartirse. Se escribe
atómicamente, con permisos de propietario cuando el sistema los admite.
Está diseñado para una sola instancia; no es una base de datos multi-réplica.
El registro de documentos cambia al siguiente domingo. Las consultas de notas
verifican que las copias identificadas sigan en su carpeta de Drive. No se verifica
el contenido editado manualmente del documento de letras.
Las tareas en curso no se reanudan solas tras reiniciar: se marcan como interrumpidas.
Un estado desconocido no se presenta como prueba de que no exista un archivo.

Las consultas `status`, `status_notes` y `status_lyrics` son de solo lectura.
Sus respuestas se construyen con hechos registrados por la aplicación, no con
afirmaciones generadas por el modelo. `lyrics_song` solicita agregar únicamente
la canción identificada al documento existente, sin reemplazar su contenido.
Las peticiones de documentos esperan las descargas pendientes del mismo remitente.
Los fallos de descarga se notifican; los documentos usan las canciones disponibles.

## Confirmaciones, correcciones y deshacer

- Los borrados de audio y notas muestran el archivo y el alcance antes de ejecutarse.
  Se confirma con botón o `confirmar`; se cancela con botón o `cancelar`.
  Cada botón lleva un identificador de propuesta: no se aceptan botones antiguos
  ni confirmaciones de otro remitente. Las propuestas caducan a los cinco minutos.
- `remove_notes` solo toca copias identificadas en Notas/, verificando carpeta y tipo.
  No toca el histórico ni sustituye la petición por un borrado del audio.
- Si hay dos canciones de Ingrid, una referencia solo al artista requiere aclaración.
  Las correcciones interpretadas invalidan la confirmación anterior.
- `tone_batch` admite de 2 a 10 canciones distintas, con ajustes completos de -12 a 12
  semitonos (sin cero). Se muestra el resumen antes de ejecutar. Cada archivo se
  vuelve a validar. Un fallo no revierte automáticamente los demás; se reporta por canción.
- `deshacer` restaura los archivos exactos del último borrado/cambio de tono, previa
  confirmación y verificación de versión/carpeta. La recuperación es de mejor esfuerzo:
  si falla a mitad, se informa y pueden quedar ambas versiones. No existe deshacer de
  ediciones de letras ni de copias de notas. Las confirmaciones y el registro de deshacer
  no se restauran tras reiniciar; caducan a los 5 y 30 minutos respectivamente.
- Las respuestas sobre una canción omiten el estado de tareas ajenas a la consulta.

## Base de acordes cuando no hay histórico

Activación opcional en la instancia:

```env
NOTES_WEB_ENABLED=true
TAVILY_API_KEY=tu_clave_del_plan_gratuito
NOTES_WEB_MONTHLY_LIMIT=50
```

También requiere Gemini habilitado y su clave. Utiliza una búsqueda Tavily `basic`
por canción sin histórico (sin `auto_parameters`, sin respuestas generadas por el
buscador), y una petición a Gemini para organizar las evidencias. No cambia planes,
no activa facturación, no reintenta ni cambia a otro proveedor cuando falla.
Para uso exclusivamente gratuito, las cuentas deben mantenerse sin pago por uso
ni facturación habilitada: el código no puede verificar ni desactivar la facturación
de una cuenta externa. El límite local es de 50 búsquedas por mes UTC por defecto,
persistido en el volumen de contexto. Se reinicia con el mes; no programa búsquedas
automáticas. Los límites 432/433 del proveedor pausan hasta el mes siguiente, y 429
pausa 15 minutos. Al agotarse la cuota se avisa y no se sube un documento vacío.

Cuando falten notas, primero pregunta si se quiere buscar una versión base en internet.
Responder `no` no hace búsquedas ni genera documentos. Tras aceptar (`si`), busca la
versión original y muestra la referencia y tonalidad encontrada; pregunta si conservar
los acordes (`original`) o transponer (`en Re`, `F#m`, etc.). Solo entonces crea en Notas/
un `BORRADOR - ... .docx` con progresiones, estructura disponible, la fuente y una lista
de puntos por revisar. Se pide a la IA usar una sola referencia y se valida que indique
la misma fuente para todas las secciones, pero no se exige coincidencia literal ni de
orden de los acordes con el texto recuperado. Se acepta la propuesta de la IA como
borrador; el DOCX advierte que los acordes no se verificaron contra las fuentes ni
contra el audio. Se mantienen los límites, símbolos de acordes y referencias válidas.
No reproduce letras ni tablaturas. No escucha audio ni verifica
el arreglo del cover: la referencia puede ser la original y siempre se indica que
debe contrastarse contra el link. Los links de nuevas descargas se guardan como referencia;
para canciones anteriores puede no disponer del link.

Ejemplo: «armame una base de notas de esa en Re». `draft_notes` identifica la canción
y `targetKey=D`. La transposición es determinista, conserva calidad y bajo invertido,
y requiere tonalidad explícita en la misma fuente. No deduce un tono absoluto de `(+2)`.
Rechaza cambios mayor/menor y transposición de fuentes que mencionen capo/cejilla sin
aclarar su efecto. Si no hay tonalidad, puede conservar los acordes fuente sin transponer.
Las preguntas web caducan en 30 minutos y se cancelan al reiniciar; no se guarda el
contenido de las páginas en el almacén de contexto. Varias canciones se preguntan una
por una. No se vuelve a buscar al elegir tonalidad. Si ya existe el DOCX de destino,
se conserva sin sobrescribir las ediciones. La búsqueda previa puede haber consumido una consulta.
La notación compleja propuesta por la IA se conserva al elegir `original`, aunque
el transpositor no la soporte. Si no se puede cambiar un símbolo, se ofrece conservar
el original en vez de perder toda la búsqueda. La tonalidad no respaldada se trata como
desconocida, sin impedir copiar los acordes originales.
Las fuentes pueden discrepar o estar equivocadas: sigue siendo un borrador, no una partitura validada.

## Referencias oficiales consultadas (19 de septiembre de 2026)

- [Google: conversaciones multiturno en GenerateContent](https://ai.google.dev/gemini-api/docs/generate-content/text-generation):
  el historial debe enviarse en cada turno para proporcionar contexto conversacional.
- [Google: salidas estructuradas](https://ai.google.dev/gemini-api/docs/generate-content/structured-output):
  un JSON válido no garantiza valores semánticamente correctos; se validan intents,
  índices y límites antes de ejecutar acciones.
- [Meta: mensajes interactivos](https://whatsapp.github.io/WhatsApp-Nodejs-SDK/api-reference/messages/interactive/):
  los botones de respuesta permiten hasta tres opciones con identificadores separados.
- [Tavily: API de búsqueda](https://docs.tavily.com/documentation/api-reference/endpoint/search):
  búsqueda básica, resultados con URL/contenido y respuestas por límites de cuota.
- [Tavily: claves y cuota gratuita](https://help.tavily.com/articles/9170796666-how-can-i-create-an-api-key):
  1,000 créditos mensuales sin tarjeta; no habilitar pago por uso para este bot.
- [Apache POI: creación de DOCX](https://poi.apache.org/components/document/quick-guide-xwpf.html):
  generación de documentos editables con XWPF.

Las pruebas locales verifican el historial, esquema, estado y despacho con dobles
del proveedor. La interpretación real de frases requiere probar Gemini en la instancia.
