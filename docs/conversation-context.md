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
Las consultas «cuántas canciones llevamos» muestran el total de la playlist; «cuántas
tienen notas» incluyen el conteo de notas asociadas verificadas en Drive.
Antes de interpretar mensajes se listan Notas/ y los documentos de la carpeta de
la fecha, con paginación y sin papelera, incluyendo archivos manuales. Las asociaciones
usan IDs conocidos de esa carpeta o coincidencias inequívocas del título normalizado
al comienzo del nombre. `driveActual.notasSinAsociar` conserva archivos ambiguos;
no se afirma que falten notas ni se crean duplicados ante esas dudas. Los documentos
se conocen por nombre/ID: no se afirma haber leído sus letras o tonalidad.
Si falla Drive, el estado es desconocido y no se crean notas a ciegas. Cada consulta
reemplaza el inventario anterior, también tras borrados. Descubrir archivos manuales
no los registra como objetivos de eliminación.
«A las demás créales notas» usa la petición general: omite copias presentes y menús
pendientes, informa de decisiones conservadas y no revoca rechazos implícitamente.
Si todas tienen copias verificadas, responde que no hace falta crear más. Consultar
la lista no cancela el menú activo. No es un chat libre sin límites: las respuestas
de estado siguen basadas en hechos observados y acciones soportadas.
Las decisiones `accepted`/`declined` se guardan en `dailyNoteDecisions` por usuario,
canción y carpeta hasta terminar el día en America/Mexico_City; sobreviven reinicios
con el almacén persistente configurado y se incluyen en el contexto de conversación.
Una petición general de notas conserva esas decisiones (también si una búsqueda
aceptada falló). Para cambiar una decisión se debe pedir esa canción explícitamente.
Antes de volver a copiar o preguntar se comprueba en Drive la presencia de las
copias registradas; no se confunde una copia de otra carpeta con la actual.
Los menús todavía pendientes se conservan al pedir notas de nuevo, sin recrearlos.
Responder `no` no hace búsquedas ni genera documentos. Tras aceptar (`si`), pregunta
el tono (`original`, `en Re`, `F#m`, etc.) ANTES de buscar. La búsqueda y el pedido a
la IA incluyen ese tono. Con el resultado crea directamente en Notas/
un `BORRADOR - ... .docx` con letra/acordes intercalados, estructura disponible y fuente.
Se pide conservar el contenido de una sola página, sin requerir campos internos de
identidad/completitud para crear el archivo. Las referencias aceptan número, cadena
numérica, URL conocida u objeto con URL/índice, tanto en la raíz como por sección.
Una referencia ausente no bloquea el contenido: no se inventa un enlace y el DOCX
avisa si no se pudo identificar la fuente concreta. No comprueba musicalmente los
acordes ni exige coincidencia literal con el texto recuperado. Se acepta la propuesta de la IA como
borrador; el DOCX advierte que los acordes no se verificaron contra las fuentes ni
contra el audio. No hay garantía independiente de identidad o completitud:
el documento sigue requiriendo revisión. Se conservan los
controles de cuota, permisos, cancelación y archivos existentes.
El prompt pide conservar letra y acordes de una misma página con reproducción permitida,
sin cruzar con el histórico ni completar letras ausentes. No incluye tablaturas. No escucha audio ni verifica
el arreglo del cover: no se autoriza sustituir por otra canción, traducción o versión.
El tono `original` no significa cambiar de canción. Siempre se indica que
debe contrastarse contra el link. Los links de nuevas descargas se guardan como referencia;
para canciones anteriores puede no disponer del link.

Ejemplo: «armame una base de notas de esa en Re». `draft_notes` identifica la canción
y `targetKey=D`. La IA recibe el tono elegido y prepara los acordes; el backend los
copia al documento sin transponerlos de nuevo ni validar musicalmente la propuesta.
Las preguntas web caducan en 30 minutos y se cancelan al reiniciar; no se guarda el
contenido de las páginas en el almacén de contexto. Varias canciones se preguntan una
por una. No se busca hasta elegir tonalidad. Si ya existe el DOCX de destino,
se conserva sin sobrescribir las ediciones ni consumir otra búsqueda.
La notación y tonalidad propuestas por la IA se conservan como datos no verificados.
La búsqueda prioriza acordes de guitarra. El DOCX sigue el estilo de ensayo del
ejemplo aportado: título y secciones en Tahoma 11 mayúsculas/negritas, espaciado compacto,
carta con márgenes de una pulgada y un único aviso de revisión al final. Los pares
`lines: [{chords, lyrics}]` conservan espacios y orden, con acordes encima de la letra.
Esos pares usan Courier New 10 para mantener las columnas al abrir en Word/Drive;
el párrafo de acordes se mantiene junto a la línea cantada. Las intros pueden llevar
solo acordes. El esquema anterior `chords: [...]` sigue funcionando, pero se avisa
cuando no hay letra utilizable. No se promete una hoja completa si la fuente llega incompleta.
Solo muestra enlaces de las fuentes citadas por las secciones. No agrega
reharmonizaciones de piano ni repeticiones inventadas. Los archivos existentes no se reemplazan.
`el original`, `el tono original` y variantes simples se resuelven localmente en la
pregunta de tono, sin requerir otra llamada a Gemini.
También se aceptan `en la original` y `en la tonalidad original`. La pregunta muestra
`D`, `D#` y `Dm`, y sigue aceptando sus nombres en español.
Solo se usa `raw_content`, nunca el resumen `content`; se excluyen páginas de video.
Las páginas de más de 40 000 caracteres se descartan en vez de recortarse.
Si faltan cuerpos, se envían hasta cinco URLs de los resultados a Tavily Extract
en un único lote `basic`, sin consultas recortadas ni proveedores alternativos.
Se reserva una unidad adicional del límite mensual local para ese lote; el contador
ahora cubre tanto búsquedas como extracciones. No habilita facturación: mantener
el proveedor en su plan gratuito sin pay-as-you-go. Si falla la extracción se conservan
las fuentes ya obtenidas; URLs inesperadas de la respuesta no se incorporan.
Los logs `stage=extract` muestran el número de páginas, resultados y descartes.
Gemini HTTP 503 se reintenta una vez tras 1–1.5 segundos, con el mismo contenido,
sin repetir búsqueda/extracción. No se reintentan 429 ni otros errores.
Las fuentes pueden discrepar o estar equivocadas: sigue siendo un borrador, no una partitura validada.

## Referencias oficiales consultadas (19 de septiembre de 2026)

### Lectura y escritura en Drive

La entrada de lenguaje natural y el menú de tonalidad usan `findSundayStructure`,
que solo busca. Si no existe la fecha, se informa sin crear carpetas. Una estructura
parcial se consulta tal cual; no se crean Playlist/Notas automáticamente al leer.
Los cambios locales de historial/estado y respuestas de WhatsApp no se consideran
escrituras de archivos en Drive.

| Acción | Acceso a Drive |
| --- | --- |
| Consultar programa, canciones, notas, letras o tareas | Solo lectura |
| Listar canciones, pedir aclaración, abrir menú de tonalidad, cancelar | Solo lectura (o sin acceso) |
| Descargar/subir canciones | Lectura y escritura; puede crear carpetas |
| Crear/copiar notas o generar/agregar letras | Lectura y escritura; puede crear carpetas |
| Buscar base web y generar DOCX autorizado | Lectura y escritura; consulta proveedores externos |
| Aplicar cambio de tono al audio | Lectura y escritura sobre archivos existentes |
| Eliminar notas/audio o deshacer | Lectura y escritura; requiere confirmación |
| Indexar histórico | Lectura y escritura del índice persistente en Drive |

`ensureSundayStructure` queda en los flujos explícitos de generación, copia y subida,
no en la interpretación de preguntas. Las carpetas creadas anteriormente no se borran.

- [Tavily Extract](https://docs.tavily.com/documentation/api-reference/endpoint/extract):
  extracción por lote de URLs, contenido recuperado y resultados fallidos.
- [Gemini: reintentos](https://ai.google.dev/gemini-api/docs/troubleshooting):
  espera acotada y reintentos limitados ante errores transitorios como HTTP 503.

Diagnóstico de búsqueda: filtrar logs por `notes-web`. Cada búsqueda tiene `search=<id>`
con consulta, cantidad de resultados, página (sin query/fragmento), título, tamaños de
`raw_content`/resumen y decisión: `accepted`, `missing_raw_content`, `video`,
`raw_content_too_large`, `unsafe_url` o `duplicate`. Incluye duración, HTTP de Tavily
y etapas `research`, `gemini`, `parsed`/`parse_failed`. No se registran claves, cuerpos
de proveedores, contenido de páginas ni letras. Estos logs no cambian la búsqueda.

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
