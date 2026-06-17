package com.elysium.apolo.integrations.mimo;

import com.elysium.apolo.config.ApoloConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MiMoClient {
    private static final Logger log = LoggerFactory.getLogger(MiMoClient.class);
    
    private final ApoloConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public MiMoClient(ApoloConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public MiMoResponse processText(String text) {
        String url = config.getMimoApiUrl();
        String apiKey = config.getMimoApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            log.error("MiMo API key is missing");
            return new MiMoResponse("NO_KEY", null, "I don't have my access key configured.");
        }

        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", "mimo-v2.5");
            
            ArrayNode messages = root.putArray("messages");
            
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Eres Apolo, un asistente inteligente local de Windows. El usuario te hablará en español.\n" +
                    "Tu tarea es entender lo que pide y devolver SIEMPRE un único objeto JSON válido sin texto adicional. TUS RESPUESTAS (campo 'text') DEBEN SER SIEMPRE EN INGLÉS.\n" +
                    "Formato JSON esperado:\n" +
                    "{\n" +
                    "  \"action\": \"TIPO_DE_ACCION\",\n" +
                    "  \"target\": \"nombre de la app (si aplica)\",\n" +
                    "  \"text\": \"texto para hablar al usuario (si aplica)\"\n" +
                    "}\n\n" +
                    "Acciones permitidas (TIPO_DE_ACCION):\n" +
                    "- OPEN_APP: para abrir apps o webs (target = spotify, discord, whatsapp, browser, explorer, etc.)\n" +
                    "- MINIMIZE: para minimizar ventanas o ir al escritorio\n" +
                    "- LOCK: para bloquear la sesión del PC\n" +
                    "- TURN_OFF_SCREEN: para apagar la pantalla/monitor\n" +
                    "- CLOSE_APP: para cerrar la ventana actual\n" +
                    "- RESTART_APP: para reiniciar una app\n" +
                    "- MEDIA_PLAY_PAUSE: pausar o reproducir música\n" +
                    "- MEDIA_NEXT: pasar a la siguiente canción\n" +
                    "- MEDIA_PREV: volver a la canción anterior\n" +
                    "- VOLUME_UP: subir el volumen\n" +
                    "- VOLUME_DOWN: bajar el volumen\n" +
                    "- VOLUME_MUTE: silenciar el sistema\n" +
                    "- SNAP_LEFT: acomodar ventana a la izquierda\n" +
                    "- SNAP_RIGHT: acomodar ventana a la derecha\n" +
                    "- MAXIMIZE_APP: maximizar ventana\n" +
                    "- TAKE_SCREENSHOT: hacer captura de pantalla\n" +
                    "- TYPE_CLIPBOARD: escribir/pegar lo que hay en el portapapeles\n" +
                    "- PROCESS_CLIPBOARD: si el usuario pide interactuar, resumir, traducir o corregir su portapapeles (target = la instruccion exacta que ha pedido el usuario).\n" +
                    "- CLEAN_SYSTEM: si el usuario pide limpiar la papelera, temporales o hacer mantenimiento del PC.\n" +
                    "- FOCUS_MODE: si el usuario pide modo concentracion o cerrar todas las demas ventanas de la barra de tareas.\n" +
                    "- SMART_DICTATION: si el usuario pide redactar un correo, mensaje o dictar un texto. El campo 'target' debe contener EXCLUSIVAMENTE el texto final generado que se va a inyectar al teclado (sin explicaciones).\n" +
                    "- SPEAK: Si el usuario te hace una pregunta, te saluda, o pide información (usa el campo 'text' con tu respuesta conversacional EN INGLÉS).\n\n" +
                    "Corrige automáticamente los pequeños errores de transcripción (ej: 'abre spot y fai' -> OPEN_APP, target: spotify. 'pasa de cancion' -> MEDIA_NEXT).");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", text);

            String requestBody = mapper.writeValueAsString(root);
            log.debug("Enviando petición a MiMo...");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode responseNode = mapper.readTree(response.body());
                String reply = responseNode.at("/choices/0/message/content").asText().trim();
                
                log.info("Respuesta cruda MiMo: {}", reply);
                
                // Extract JSON if it's wrapped in markdown
                if (reply.startsWith("```json")) {
                    reply = reply.substring(7);
                    if (reply.endsWith("```")) {
                        reply = reply.substring(0, reply.length() - 3).trim();
                    }
                }
                
                try {
                    JsonNode jsonReply = mapper.readTree(reply);
                    String action = jsonReply.path("action").asText(null);
                    String target = jsonReply.path("target").asText(null);
                    String speakText = jsonReply.path("text").asText(null);
                    return new MiMoResponse(action, target, speakText);
                } catch (Exception e) {
                    log.error("MiMo devolvió algo que no es JSON válido: {}", reply);
                    return new MiMoResponse("UNKNOWN", null, "I couldn't process the response format.");
                }
            } else {
                log.error("API call failed: {} - {}", response.statusCode(), response.body());
                return new MiMoResponse("UNKNOWN", null, "Connection error with Xiaomi servers.");
            }
        } catch (Exception e) {
            log.error("Exception calling MiMo: {}", e.getMessage(), e);
            return new MiMoResponse("UNKNOWN", null, "There was an error trying to process the command.");
        }
    }

    public String processClipboardTask(String instruction, String clipboardContent) {
        String url = config.getMimoApiUrl();
        String apiKey = config.getMimoApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            return "API Key not configured.";
        }

        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", "mimo-v2.5");
            
            ArrayNode messages = root.putArray("messages");
            
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are an AI assistant processing the user's clipboard content. " +
                    "Follow the given instruction precisely. " +
                    "Your entire response MUST be IN ENGLISH unless the instruction explicitly asks to translate to another language. " +
                    "Do NOT include conversational filler like 'Here is the translation'. " +
                    "Just output the final result.");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Instruction: " + instruction + "\n\nClipboard content:\n" + clipboardContent);

            String requestBody = mapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode responseNode = mapper.readTree(response.body());
                return responseNode.at("/choices/0/message/content").asText().trim();
            } else {
                log.error("API call failed: {} - {}", response.statusCode(), response.body());
                return "There was an error processing the clipboard with Xiaomi MiMo.";
            }
        } catch (Exception e) {
            log.error("Exception calling MiMo for clipboard: {}", e.getMessage(), e);
            return "A critical error occurred while contacting the AI server.";
        }
    }
}
