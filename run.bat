@echo off
REM ============================================
REM  ELYSIUM - Apolo V1
REM  Script de arranque para Windows
REM ============================================

echo.
echo  ==========================================
echo   ELYSIUM - Apolo V1
echo   Asistente de automatizacion por voz
echo  ==========================================
echo.

REM Verificar Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java no encontrado. Instala Java 21 o superior.
    echo Descarga: https://adoptium.net/
    pause
    exit /b 1
)

REM Verificar modelo Vosk
if not exist "models\vosk-model-small-es-0.42" (
    echo [ERROR] Modelo Vosk no encontrado.
    echo Descarga el modelo desde: https://alphacephei.com/vosk/models
    echo Y extráelo en: models\vosk-model-small-es-0.42
    pause
    exit /b 1
)

REM Compilar si es necesario
if not exist "target\elysium-apolo-1.0.0-SNAPSHOT.jar" (
    echo Compilando proyecto...
    call mvn package -DskipTests -q
    if errorlevel 1 (
        echo [ERROR] Error compilando el proyecto.
        pause
        exit /b 1
    )
)

echo Iniciando Apolo...
echo Di "Apolo" seguido de un comando para activar.
echo.

REM Ejecutar con el fat JAR
java -jar target\elysium-apolo-1.0.0-SNAPSHOT.jar

pause
