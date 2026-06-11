@echo off
REM ============================================
REM  ELYSIUM - Apolo V1
REM  Script de compilación y empaquetado
REM ============================================

echo Compilando y empaquetando Apolo...

REM Verificar Maven
call mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven no encontrado. Instala Maven 3.9+ o configura el PATH.
    pause
    exit /b 1
)

REM Compilar y empaquetar
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] Error en la compilación.
    pause
    exit /b 1
)

echo.
echo ==========================================
echo  Build completado.
echo  JAR: target\elysium-apolo-1.0.0-SNAPSHOT.jar
echo ==========================================
echo.
echo Para ejecutar:
echo   java -jar target\elysium-apolo-1.0.0-SNAPSHOT.jar
echo.
echo Para empaquetar como .exe (futuro):
echo   jpackage --name Apolo --input target --main-jar elysium-apolo-1.0.0-SNAPSHOT.jar --main-class com.elysium.apolo.app.ApoloMain --type exe
echo.

pause
