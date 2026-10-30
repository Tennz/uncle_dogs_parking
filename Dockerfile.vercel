# Vercel container deployment for the Java 17 HTTP server.
# Persistent application data lives in the configured Supabase PostgreSQL database.
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
COPY src ./src
RUN mkdir -p out lib && find src -name '*.java' -print > sources.txt \
    && javac -encoding UTF-8 -d out @sources.txt \
    && curl -fsSL --retry 3 https://jdbc.postgresql.org/download/postgresql-42.7.14.jar -o lib/postgresql-42.7.14.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/out ./out
COPY --from=build /build/lib/postgresql-42.7.14.jar ./lib/postgresql-42.7.14.jar
COPY assets ./assets
ENV PORT=80 \
    DEPLOYMENT_TARGET=vercel \
    SHOW_SWING=false \
    OPEN_BROWSER=false \
    DATA_DIR=/tmp/data
EXPOSE 80
CMD ["sh", "-c", "exec java -Djava.awt.headless=true -cp out:lib/postgresql-42.7.14.jar Main"]
