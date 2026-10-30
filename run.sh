#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out lib
if [[ -n "${DATABASE_URL:-}" ]] || grep -qE '^DATABASE_URL=.+$' config.txt; then
  if [[ -z "${DATABASE_PASSWORD:-}" ]] && ! grep -qE '^DATABASE_PASSWORD=.+$' config.txt; then
    read -r -s -p 'Supabase database password (input hidden): ' DATABASE_PASSWORD
    echo
    export DATABASE_PASSWORD
  fi
  if [[ ! -f lib/postgresql-42.7.14.jar ]]; then
    echo "Downloading PostgreSQL JDBC driver for Supabase mode..."
    curl -fL --retry 2 -o lib/postgresql-42.7.14.jar https://jdbc.postgresql.org/download/postgresql-42.7.14.jar
  fi
fi
find src -name '*.java' -print > sources.txt
javac --release 17 -encoding UTF-8 -d out @sources.txt
if [[ -f lib/postgresql-42.7.14.jar ]]; then
  exec java -cp "out:lib/postgresql-42.7.14.jar" Main
else
  exec java -cp out Main
fi
