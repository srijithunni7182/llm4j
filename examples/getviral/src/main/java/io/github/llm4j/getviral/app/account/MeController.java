package io.github.llm4j.getviral.app.account;

import io.github.llm4j.getviral.app.memory.MemorySync;
import io.github.llm4j.getviral.app.memory.VoiceSamples;
import io.github.llm4j.getviral.app.media.MediaStore;
import io.github.llm4j.getviral.app.runs.RunRecord;
import io.github.llm4j.getviral.app.runs.RunRepository;
import io.github.llm4j.getviral.app.runs.RunService;
import io.github.llm4j.getviral.app.runs.RunStatus;
import io.github.llm4j.getviral.engine.GetViralEngine;
import io.github.llm4j.getviral.config.GetViralConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in creator: profile, onboarding progress, quota, memory, voice, and account deletion. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final RunService runService;
    private final RunRepository runs;
    private final VoiceSamples voices;
    private final MemorySync memory;
    private final GetViralEngine engine;
    private final GetViralConfig config;
    private final MediaStore mediaStore;
    private final ConnectionsView connections;

    public MeController(CurrentUser currentUser, UserRepository users, RunService runService, RunRepository runs,
                        VoiceSamples voices, MemorySync memory, GetViralEngine engine, GetViralConfig config,
                        MediaStore mediaStore, ConnectionsView connections) {
        this.currentUser = currentUser;
        this.users = users;
        this.runService = runService;
        this.runs = runs;
        this.voices = voices;
        this.memory = memory;
        this.engine = engine;
        this.config = config;
        this.mediaStore = mediaStore;
        this.connections = connections;
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        UserAccount user = currentUser.require();
        RunService.Quota quota = runService.quota(user.getId());
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("id", user.getId());
        me.put("email", user.getEmail());
        me.put("name", user.getName());
        me.put("avatarUrl", user.getAvatarUrl());
        me.put("handle", user.getHandle());
        me.put("niche", user.getNiche());
        me.put("tone", user.getTone());
        me.put("audience", user.getAudience());
        me.put("region", user.getRegion());
        me.put("onboardingStep", user.getOnboardingStep().name());
        me.put("quota", Map.of("used", quota.used(), "limit", quota.limit()));
        me.put("activeRun", runs.findActive(user.getId(), RunStatus.ACTIVE).stream().findFirst().map(RunRecord::getId).orElse(null));
        me.put("voiceSamples", voices.count(user.getId()));
        me.put("connections", connections.forUser(user.getId()));
        return me;
    }

    public record Profile(
            @NotBlank @Size(max = 40) @Pattern(regexp = "@?[A-Za-z0-9._]{1,39}", message = "may only use letters, numbers, . and _") String handle,
            @NotBlank @Size(max = 80) String niche,
            @NotBlank @Size(max = 80) String tone,
            @Size(max = 300) String audience,
            @Pattern(regexp = "[A-Za-z]{2}", message = "must be a two-letter country code") String region) { }

    @PutMapping("/me/profile")
    @Transactional
    Map<String, Object> profile(@Valid @RequestBody Profile body) {
        UserAccount user = currentUser.require();
        user.updateProfile(body.handle().replaceFirst("^@", ""), body.niche().strip(), body.tone().strip(),
                body.audience() == null ? null : body.audience().strip(), body.region() == null ? "US" : body.region().toUpperCase());
        if (user.getOnboardingStep() == OnboardingStep.PROFILE) user.onboardingStep(OnboardingStep.CONNECT);
        users.save(user);
        return me();
    }

    public record Advance(@NotBlank String to) { }

    /** Moves onboarding forward (never backwards, never past what's been done). */
    @PostMapping("/me/onboarding")
    @Transactional
    Map<String, Object> advance(@Valid @RequestBody Advance body) {
        UserAccount user = currentUser.require();
        OnboardingStep target = OnboardingStep.valueOf(body.to().toUpperCase());
        if (user.getHandle() != null && target.ordinal() > user.getOnboardingStep().ordinal()) {
            user.onboardingStep(target);
            users.save(user);
        }
        return me();
    }

    public record Voice(@Size(max = 20) List<@Size(max = 5000) String> posts) { }

    @PostMapping("/me/voice")
    Map<String, Object> voice(@Valid @RequestBody Voice body) {
        UserAccount user = currentUser.require();
        int added = voices.add(user.getId(), body.posts() == null ? List.of() : body.posts(), "pasted");
        return Map.of("added", added, "total", voices.count(user.getId()));
    }

    @GetMapping("/memory")
    Map<String, Object> memories() {
        UserAccount user = currentUser.require();
        memory.pull(user.getId());
        return Map.of("memories", engine.memories(user.getId()));
    }

    public record Feedback(@NotBlank @Pattern(regexp = "x|reel|youtube") String platform, boolean loved, @Size(max = 300) String detail) { }

    @PostMapping("/feedback")
    Map<String, Object> feedback(@Valid @RequestBody Feedback body) {
        UserAccount user = currentUser.require();
        memory.pull(user.getId());
        engine.feedback(user.getId(), body.platform(), body.loved(), body.detail());
        memory.push(user.getId());
        return Map.of("ok", true);
    }

    /** Every image and video this creator has generated, newest first. */
    @GetMapping("/library/media")
    List<Map<String, Object>> libraryMedia() {
        UserAccount user = currentUser.require();
        return runs.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 200)).stream()
                .filter(r -> r.getPackJson() != null)
                .flatMap(r -> {
                    Object media = runService.pack(r).get("media");
                    if (!(media instanceof List<?> list)) return Stream.empty();
                    return list.stream().filter(m -> m instanceof Map<?, ?>).map(m -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        ((Map<?, ?>) m).forEach((k, v) -> item.put(String.valueOf(k), v));
                        item.put("runId", r.getId());
                        item.put("idea", r.getIdea());
                        item.put("createdAt", r.getCreatedAt().toString());
                        return item;
                    });
                })
                .toList();
    }

    /** Deletes the account and everything in it: runs, media, memory, voice samples, connections. */
    @DeleteMapping("/me")
    @Transactional
    Map<String, Object> delete(HttpServletRequest request) throws IOException {
        UserAccount user = currentUser.require();
        List<RunRecord> all = runs.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 10_000));
        for (RunRecord run : all) {
            mediaStore.deleteRun(run.getId());
            deleteTree(config.dataDir().resolve("media").resolve(run.getId()));
        }
        Files.deleteIfExists(memory.file(user.getId()));
        users.delete(user); // cascades to runs, events, questions, connections, voice samples, memory
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        return Map.of("deleted", true);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
