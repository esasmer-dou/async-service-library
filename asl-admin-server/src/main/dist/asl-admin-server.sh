#!/bin/sh
# Starts the ASL admin server with the low-footprint JVM options next to this script.
# Extra JVM options: ASL_ADMIN_JAVA_OPTS. Server settings: --key=value arguments or ASL_ADMIN_SERVER_* variables.
DIR=$(cd "$(dirname "$0")" && pwd)
JAR=${ASL_ADMIN_JAR:-$(ls "$DIR"/asl-admin-server-*-exec.jar 2>/dev/null | head -n 1)}
if [ -z "$JAR" ]; then
  echo "asl-admin-server-<version>-exec.jar not found next to $0 (or set ASL_ADMIN_JAR)" >&2
  exit 1
fi
# glibc creates one malloc arena per thread by default; two are enough here and keep RSS flat.
export MALLOC_ARENA_MAX=${MALLOC_ARENA_MAX:-2}
exec java @"$DIR/asl-admin-server.jvmopts" $ASL_ADMIN_JAVA_OPTS -jar "$JAR" "$@"
