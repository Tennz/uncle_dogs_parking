FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY src ./src
COPY assets ./assets
COPY data ./data
RUN mkdir -p out lib && find src -name '*.java' -print > sources.txt && javac -encoding UTF-8 -d out @sources.txt && curl -fsSL https://jdbc.postgresql.org/download/postgresql-42.7.14.jar -o lib/postgresql-42.7.14.jar
ENV SHOW_SWING=false
EXPOSE 10000
CMD ["sh", "-c", "java -cp out:lib/postgresql-42.7.14.jar Main"]
