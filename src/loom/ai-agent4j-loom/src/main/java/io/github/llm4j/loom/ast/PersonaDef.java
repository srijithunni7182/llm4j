package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/** A persona declared in the script: {@code persona Mentor { role: "…" tone: "…" constraints: ["…"] }}. */
public class PersonaDef implements Node {
    private final String name;
    private String role;
    private String expertise;
    private String tone;
    private String description;
    private final List<String> constraints = new ArrayList<>();
    private int line;

    public PersonaDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getExpertise() { return expertise; }
    public void setExpertise(String expertise) { this.expertise = expertise; }
    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getConstraints() { return constraints; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
