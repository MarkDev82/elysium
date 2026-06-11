@echo off
REM ============================================
REM  ELYSIUM - Apolo V1
REM  Script de empaquetado con jpackage
REM ============================================
REM
REM  ESTRATEGIA DE PACKAGING:
REM  1. Generar app-image (directorio con app + JRE embebido)
REM  2. Copiar modelo Vosk y config al directorio de la app
REM  3. (Opcional) Generar instalador .exe/.msi desde el app-image
REM
REM  REQUISITOS:
REM  - JDK 21+ con jpackage (incluido en Adoptium/Temurin)
REM  - WiX Toolset 3.x para generar .msi (opcional)
REM  - Maven para compilar el fat JAR primero
REM ============================================

setlocal

set APP_NAME=Apolo
set APP_VERSION=1.0.0
set MAIN_JAR=elysium-apolo-1.0.0-SNAPSHOT.jar
set MAIN_CLASS=com.elysium.apolo.app.ApoloMain
set OUTPUT_DIR=dist
set INPUT_DIR=target

echo.
echo ==========================================
echo  ELYSIUM - Apolo V1 - Packaging
echo ==========================================
echo.

REM Verificar que el JAR existe
if not exist "%INPUT_DIR%\%MAIN_JAR%" (
    echo [ERROR] JAR no encontrado: %INPUT_DIR%\%MAIN_JAR%
    echo Ejecuta primero: mvn clean package -DskipTests
    pause
    exit /b 1
)

REM Verificar que jpackage existe
jpackage --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jpackage no encontrado.
    echo Necesitas JDK 21+ con jpackage incluido.
    echo Descarga: https://adoptium.net/
    pause
    exit /b 1
)

REM Verificar que el modelo Vosk existe
if not exist "models\vosk-model-small-es-0.42" (
    echo [ERROR] Modelo Vosk no encontrado en models\vosk-model-small-es-0.42
    pause
    exit /b 1
)

REM Limpiar directorio de salida
if exist "%OUTPUT_DIR%" rmdir /s /q "%OUTPUT_DIR%"

echo [1/3] Generando app-image...
jpackage ^
    --type app-image ^
    --name "%APP_NAME%" ^
    --input "%INPUT_DIR%" ^
    --main-jar "%MAIN_JAR%" ^
    --main-class "%MAIN_CLASS%" ^
    --dest "%OUTPUT_DIR%" ^
    --app-version "%APP_VERSION%" ^
    --java-options "-Dapolo.models.dir=models" ^
    --java-options "-Dfile.encoding=UTF-8"

if errorlevel 1 (
    echo [ERROR] Error generando app-image
    pause
    exit /b 1
)

echo.
echo [2/3] Copiando modelo Vosk y configuración...

REM Copiar modelo Vosk al directorio de la app
xcopy /E /I /Q "models\vosk-model-small-es-0.42" "%OUTPUT_DIR%\%APP_NAME%\models\vosk-model-small-es-0.42"

REM Copiar configuración
if exist "apolo-config.properties" (
    copy "apolo-config.properties" "%OUTPUT_DIR%\%APP_NAME%\apolo-config.properties"
)

echo.
echo [3/3] App-image generado correctamente.
echo.
echo ==========================================
echo  RESULTADO
echo ==========================================
echo  Directorio: %OUTPUT_DIR%\%APP_NAME%\
echo  Ejecutable: %OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe
echo  Modelo Vosk: %OUTPUT_DIR%\%APP_NAME%\models\
echo  Config: %OUTPUT_DIR%\%APP_NAME%\apolo-config.properties
echo.
echo  Para ejecutar directamente:
echo    %OUTPUT_DIR%\%APP_NAME%\%APP_NAME%.exe
echo.
echo  Para generar instalador .exe (requiere Inno Setup o similar):
echo    jpackage --type exe --app-image %OUTPUT_DIR%\%APP_NAME% --dest %OUTPUT_DIR%\installer
echo.
echo  Para generar instalador .msi (requiere WiX Toolset):
echo    jpackage --type msi --app-image %OUTPUT_DIR%\%APP_NAME% --dest %OUTPUT_DIR%\installer --win-upgrade-uuid "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
echo.

pause
