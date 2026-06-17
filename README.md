<div align="center">
  <h1>ELYSIUM - Apolo V1</h1>
  <p><strong>Asistente Inteligente de Automatización por Voz para Windows</strong></p>
  <p><i>Impulsado por reconocimiento de voz local y razonamiento avanzado mediante LLM</i></p>
</div>

---

## Descripción General

Apolo es un asistente local altamente capacitado, diseñado para eliminar la barrera entre los comandos de voz naturales y la automatización compleja de escritorio. Desarrollado en Java 21, Apolo actúa como un puente hacia tu entorno Windows, permitiéndote ejecutar tareas avanzadas sin separar las manos del teclado.

A diferencia de los asistentes tradicionales basados en estructuras de comandos rígidas, Apolo se integra de manera nativa con **MiMo**, un modelo de lenguaje de gran tamaño (LLM). Esto le otorga la capacidad de interpretar instrucciones en lenguaje natural y traducirlas a acciones precisas dentro del sistema.

## Características Principales

*   **Reconocimiento de Voz Local Continuo:** Respaldado por la API de Vosk, Apolo escucha en segundo plano de manera completamente local, asegurando privacidad total y latencia cero al esperar su palabra de activación.
*   **Razonamiento Impulsado por IA:** El procesamiento del lenguaje natural se delega al modelo MiMo. Apolo comprende el contexto, corrige pequeños errores de transcripción y deduce la acción correcta aunque la instrucción no sea formulada de forma exacta.
*   **Dictado Inteligente (Smart Dictation):** Apolo puede redactar, traducir o corregir texto en tiempo real. Simplemente ubica el cursor en cualquier aplicación, pide a Apolo que redacte un mensaje, y la inteligencia artificial generará el texto inyectándolo directamente en tu pantalla simulando el teclado.
*   **Asistente del Portapapeles:** Apolo puede leer el contenido de la memoria del portapapeles de Windows para ejecutar tareas sobre él. Pídele que resuma, traduzca o explique la información que acabas de copiar.
*   **Integración Profunda con Windows:**
    *   **Modo Concentración:** Despeja instantáneamente tu espacio de trabajo cerrando de forma segura todas las ventanas en segundo plano, protegiendo entornos de desarrollo esenciales (ej: VS Code, Windows Terminal).
    *   **Mantenimiento del Sistema:** Automatiza la limpieza de archivos temporales y el vaciado de la papelera de reciclaje mediante scripts nativos de PowerShell.
    *   **Gestión de Aplicaciones:** Lanza cualquier aplicación configurada, abre el navegador web o interactúa con funciones del sistema operativo.
    *   **Control Multimedia:** Gestiona el volumen del sistema, controla la reproducción de medios, bloquea la estación de trabajo o apaga el monitor con una simple orden.

## Arquitectura y Tecnologías

Apolo está construido utilizando tecnologías modernas en Java e integraciones nativas robustas.

*   **Lenguaje:** Java 21
*   **Voz a Texto (STT):** API de Vosk (Reconocimiento continuo y sin conexión)
*   **Procesamiento de Lenguaje (NLP):** Integración API REST con modelos MiMo
*   **Interfaz de SO Nativo:** Java Native Access (JNA) y scripts de PowerShell
*   **Automatización de Hardware:** `java.awt.Robot` para inyección de pulsaciones y manipulación de interfaz
*   **Sistema de Construcción:** Apache Maven

## Requisitos Previos

Para compilar y ejecutar Apolo localmente, es indispensable contar con:

*   **Java Development Kit (JDK) 21** o superior.
*   **Windows 10 / 11** (La ejecución de PowerShell requiere un entorno Windows).
*   **Micrófono** conectado y configurado como dispositivo de grabación predeterminado.
*   **Modelo de Voz Vosk en Español** (versión `vosk-model-small-es-0.42` recomendada).

## Instalación y Configuración

1.  **Clonar el Repositorio:**
    ```bash
    git clone https://github.com/tu-usuario/elysium-apolo.git
    cd elysium-apolo
    ```

2.  **Descargar el Modelo de Voz:**
    *   Descarga el modelo en español desde la [página oficial de modelos de Vosk](https://alphacephei.com/vosk/models).
    *   Extrae su contenido en un directorio llamado `models/vosk-model-small-es-0.42` en la raíz del proyecto.

3.  **Configuración del Entorno:**
    *   Crea tu archivo de configuración a partir del archivo de ejemplo:
        ```bash
        cp apolo-config.properties.example apolo-config.properties
        ```
    *   Edita `apolo-config.properties` e introduce tu clave de API de MiMo junto con las rutas de las aplicaciones que desees controlar.
        ```properties
        mimo.api.key=TU_CLAVE_API_AQUI
        wake.word=apolo
        app.browser=C:/Program Files/Google/Chrome/Application/chrome.exe
        ```

4.  **Compilar el Proyecto:**
    Construye el proyecto y empaqueta el archivo ejecutable JAR utilizando Maven.
    ```bash
    ./mvnw clean package
    ```

## Uso

Inicia Apolo utilizando el script por lotes (batch script) proporcionado o ejecutándolo directamente con Java:

```bash
run.bat
```

Una vez en ejecución, Apolo inicializará el micrófono y cargará los modelos de voz. La consola mostrará un mensaje cuando Apolo esté listo.

Para enviar un comando, pronuncia la palabra de activación seguida de tu instrucción.

**Ejemplos de comandos en lenguaje natural:**
*   *"Apolo, activa el modo concentración."* (Inicia el Focus Mode)
*   *"Apolo, redacta un correo formal diciendo que llegaré tarde por el tráfico."* (Inicia el Dictado Inteligente)
*   *"Apolo, haz un resumen de lo que tengo en el portapapeles."* (Inicia el Asistente del Portapapeles)
*   *"Apolo, limpia el sistema."* (Inicia el Mantenimiento del Sistema)
*   *"Apolo, abre Spotify."* (Lanza la aplicación configurada)

## Escalabilidad

Incorporar nuevas capacidades a Apolo requiere únicamente tres pasos:
1.  **Definir la Acción:** Añadir el nuevo identificador al enumerador `CommandType.java`.
2.  **Instruir a la IA:** Actualizar la directiva principal (System Prompt) en `MiMoClient.java` para que el modelo sepa en qué contextos debe disparar la nueva acción.
3.  **Implementar la Ejecución:** Crear la lógica subyacente en `WindowsActions.java` y enlazarla en el bloque *switch* de `ActionExecutor.java`.

## Licencia

Este proyecto es de carácter propietario y confidencial. Queda estrictamente prohibida la copia no autorizada de este documento o su código fuente por cualquier medio.
