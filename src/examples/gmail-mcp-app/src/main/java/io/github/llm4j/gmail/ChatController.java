package io.github.llm4j.gmail;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GmailAgentService agentService;

    public ChatController(GmailAgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chat(
            @RequestBody Map<String, String> request) {
        String message = request.get("message");
        return agentService.chat(message);
    }

    @GetMapping("/tools")
    public java.util.List<Map<String, Object>> getTools() {
        return agentService.getTools();
    }
}
