@echo off
setlocal
set SCRIPT_DIR=%~dp0
set JAR_PATH=%SCRIPT_DIR%build\libs\hybris-impex-converter-1.0.0.jar

if not exist "%JAR_PATH%" (
    echo [INFO] Building application with Gradle...
    call "%SCRIPT_DIR%gradlew.bat" jar
)

java -jar "%JAR_PATH%" %*
