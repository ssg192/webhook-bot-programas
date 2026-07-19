# --- Etapa 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package -DskipTests

# --- Etapa 2: runtime ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# yt-dlp necesita python3; PitchShifter necesita ffmpeg
# Deno: motor de JS que yt-dlp usa para resolver los "retos" (n-challenge) de YouTube.
# Sin esto, yt-dlp puede obtener la info del video pero falla al bajar el archivo real (403).
ARG CACHEBUST=1
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip ffmpeg curl unzip \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh \
    && pip3 install --break-system-packages -U yt-dlp-ejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/quarkus-app/lib/ ./lib/
COPY --from=build /app/target/quarkus-app/*.jar ./
COPY --from=build /app/target/quarkus-app/app/ ./app/
COPY --from=build /app/target/quarkus-app/quarkus/ ./quarkus/

EXPOSE 8080
ENV QUARKUS_HTTP_HOST=0.0.0.0

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]