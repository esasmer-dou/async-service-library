@echo off
rem Starts the ASL admin server with the low-footprint JVM options next to this script.
rem Extra JVM options: ASL_ADMIN_JAVA_OPTS. Server settings: --key=value arguments or ASL_ADMIN_SERVER_* variables.
setlocal
set "DIR=%~dp0"
if "%ASL_ADMIN_JAR%"=="" for %%f in ("%DIR%asl-admin-server-*-exec.jar") do set "ASL_ADMIN_JAR=%%f"
if "%ASL_ADMIN_JAR%"=="" (
  echo asl-admin-server-^<version^>-exec.jar not found next to this script ^(or set ASL_ADMIN_JAR^) 1>&2
  exit /b 1
)
java @"%DIR%asl-admin-server.jvmopts" %ASL_ADMIN_JAVA_OPTS% -jar "%ASL_ADMIN_JAR%" %*
