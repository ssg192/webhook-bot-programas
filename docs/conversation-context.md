# Contexto de canciones y documentos

El bot entrega a Gemini la playlist actual, el registro de tareas de este proceso,
las elecciones pendientes de notas y hasta 16 mensajes recientes con rol usuario/bot
(máximo 2500 caracteres por mensaje, separados por número, caducidad de 30 minutos).
Solo se registran respuestas salientes cuyo envío no produjo un error.
Los mensajes y estados están en memoria: no sobreviven a un reinicio. El registro
de documentos cambia al siguiente domingo y no verifica cambios manuales en Drive.
Un estado desconocido no se presenta como prueba de que no exista un archivo.

Las consultas `status`, `status_notes` y `status_lyrics` son de solo lectura.
Sus respuestas se construyen con hechos registrados por la aplicación, no con
afirmaciones generadas por el modelo. `lyrics_song` solicita agregar únicamente
la canción identificada al documento existente, sin reemplazar su contenido.
Las peticiones de documentos esperan las descargas pendientes del mismo remitente.
Los fallos de descarga se notifican; los documentos usan las canciones disponibles.

## Referencias oficiales consultadas (19 de septiembre de 2026)

- [Google: conversaciones multiturno en GenerateContent](https://ai.google.dev/gemini-api/docs/generate-content/text-generation):
  el historial debe enviarse en cada turno para proporcionar contexto conversacional.
- [Google: salidas estructuradas](https://ai.google.dev/gemini-api/docs/generate-content/structured-output):
  un JSON válido no garantiza valores semánticamente correctos; se validan intents,
  índices y límites antes de ejecutar acciones.

Las pruebas locales verifican el historial, esquema, estado y despacho con dobles
del proveedor. La interpretación real de frases requiere probar Gemini en la instancia.
