package io.github.llm4j.loom.parser;

import io.github.llm4j.loom.ast.*;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.loom.lexer.Token;
import io.github.llm4j.loom.lexer.TokenType;
import java.util.List;

public class LoomParser {
    private final List<Token> tokens;
    private int current = 0;

    public LoomParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public LoomScript parseScript() {
        LoomScript script = new LoomScript();

        while (!isAtEnd()) {
            if (match(TokenType.AGENT)) {
                script.addAgent(parseAgent());
            } else if (match(TokenType.IMPORT)) {
                int line = previous().getLine();
                script.addImport(parseImport(), line);
            } else if (match(TokenType.WORKFLOW)) {
                script.addWorkflow(parseWorkflow());
            } else if (match(TokenType.MCP)) {
                script.addMcpServer(parseMcpServer());
            } else if (match(TokenType.AUDIT)) {
                script.setAuditConfig(parseAuditConfig());
            } else if (match(TokenType.KNOWLEDGE)) {
                script.addKnowledgeBase(parseKnowledgeBase());
            } else if (match(TokenType.ROUTING)) {
                script.addRoutingPolicy(parseRoutingPolicy());
            } else if (match(TokenType.SCHEDULE)) {
                script.addSchedule(parseSchedule());
            } else if (match(TokenType.TOOL)) {
                script.addTool(parseToolDef());
            } else if (isNamedBlock("provider")) {
                advance();
                script.addProvider(parseProviderDef());
            } else if (check(TokenType.PERSONA)
                    && tokens.size() > current + 1
                    && tokens.get(current + 1).getType() == TokenType.IDENTIFIER) {
                advance();
                script.addPersona(parsePersonaDef());
            } else if (isNamedBlock("intentclassifier")) {
                advance();
                script.addIntentClassifier(parseIntentClassifier());
            } else if (isNamedBlock("extractor")) {
                advance();
                script.addExtractor(parseExtractor());
            } else if (isNamedBlock("evaluator")) {
                advance();
                script.addEvaluator(parseEvaluator());
            } else if (isPolicyDeclaration()) {
                advance();
                script.addPolicy(parsePolicy());
            } else if (isBudgetKeyword()) {
                // Contextual keyword: `budget` stays usable as a variable name everywhere else.
                Token keyword = advance();
                if (script.getBudget() != null)
                    throw error(keyword, "Only one top-level budget block is allowed.");
                script.setBudget(parseBudgetBlock(false));
            } else if (isDecisionStart()) {
                script.addDecision(parseDecision());
            } else if (check(TokenType.LADDER_DECL)) {
                Token decl = advance();
                script.addLadder(LadderParser.parseLadder(decl.getValue(), decl.getLine()));
            } else if (check(TokenType.MEASURE_DECL)) {
                Token decl = advance();
                script.addMeasure(LadderParser.parseMeasure(decl.getValue(), decl.getLine()));
            } else if (isNamedAttribute("prompts")) {
                // Contextual: `prompts` stays usable as a variable name everywhere else.
                Token keyword = advance();
                advance(); // the colon
                if (script.getPromptsDir() != null)
                    throw error(keyword, "Only one prompts: folder can be named.");
                script.setPromptsDir(
                        consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a folder for prompts, e.g. prompts: \"./prompts\".")
                                .getValue(),
                        keyword.getLine());
            } else if (isNamedAttribute("media_root")) {
                // Contextual, like prompts: stays usable as a name everywhere else.
                Token keyword = advance();
                advance(); // the colon
                if (script.getMediaRoot() != null)
                    throw error(keyword, "Only one media_root can be given.");
                script.setMediaRoot(
                        consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a folder for media_root, e.g. media_root: \"./assets\".")
                                .getValue());
            } else if (isWord("media") && peekAt(1).getType() == TokenType.LBRACE) {
                Token keyword = advance();
                if (script.getMediaMaxBytes() != null || script.getMediaMaxItems() != null)
                    throw error(keyword, "Only one media block can be given.");
                parseMediaBlock(script);
            } else if (isNamedAttribute("max_children") || isNamedAttribute("max_depth")) {
                parseChildLimit(script);
            } else if (isNamedAttribute("substitution")) {
                // Contextual, like shared_prompt: stays usable as a name everywhere else.
                Token keyword = advance();
                advance(); // the colon
                Token mode = advance();
                if (!"braces".equals(mode.getValue()) && !"legacy".equals(mode.getValue()))
                    throw error(
                            mode,
                            "substitution is braces (only {name} is replaced) or legacy (a bare name is replaced too), not "
                                    + mode.getValue()
                                    + ".");
                script.setSubstitution(mode.getValue());
            } else if (isNamedAttribute("shared_prompt")) {
                // Contextual: stays usable as a name everywhere else.
                Token keyword = advance();
                advance(); // the colon
                if (script.getSharedPrompt() != null || script.getSharedPromptRef() != null)
                    throw error(keyword, "Only one shared_prompt can be given.");
                if (isWord("prompt") && peekAt(1).getType() == TokenType.STRING_LITERAL) {
                    advance();
                    script.setSharedPrompt(null, advance().getValue(), keyword.getLine());
                } else {
                    Token text =
                            consume(
                                    TokenType.STRING_LITERAL,
                                    "Write shared_prompt: \"text for every agent\" or shared_prompt: prompt \"house-rules\" (a prompt file).");
                    if (text.getValue().isBlank()) throw error(text, "shared_prompt is empty.");
                    script.setSharedPrompt(text.getValue(), null, keyword.getLine());
                }
            } else if (isDeclaration("remote")) {
                script.addRemote(
                        parseNamedSettings(
                                io.github.llm4j.loom.ast.RemoteDef::new,
                                "remote",
                                name ->
                                        script.getRemotes().stream()
                                                .anyMatch(r -> r.getName().equals(name))));
            } else if (isDeclaration("agent_channel")) {
                script.addAgentChannel(
                        parseNamedSettings(
                                io.github.llm4j.loom.ast.AgentChannelDef::new,
                                "agent_channel",
                                name ->
                                        script.getAgentChannels().stream()
                                                .anyMatch(c -> c.getName().equals(name))));
            } else if (isDeclaration("expose")) {
                script.addExpose(parseExpose(script));
            } else if (isWord("supervise")
                    && peekAt(1).getType() == TokenType.IDENTIFIER
                    && peekAt(2).getType() == TokenType.LBRACE) {
                Token keyword = advance();
                script.addSupervision(parseSupervise(keyword));
            } else if (isBlockKeyword("intervention")) {
                Token keyword = advance();
                if (script.getIntervention() != null)
                    throw error(keyword, "Only one intervention block is allowed.");
                script.setIntervention(parseIntervention(keyword));
            } else if (isBlockKeyword("rate_limits")) {
                Token keyword = advance();
                if (script.getRateLimits() != null)
                    throw error(keyword, "Only one rate_limits block is allowed.");
                script.setRateLimits(parseRateLimits());
            } else {
                throw error(
                        peek(),
                        "Expected 'agent', 'workflow', 'tool', 'provider', 'persona', 'intentclassifier', 'extractor', 'mcp', 'audit', 'knowledge', 'routing', 'schedule', 'budget' or 'rate_limits' declaration, but got: "
                                + peek().getType());
            }
        }

        script.applyAgentLadders();
        script.applyTrustLadders();
        return script;
    }

    /**
     * True at an identifier with this name followed by a colon, such as {@code prompt:}; the word
     * is not reserved anywhere else.
     */
    private boolean isNamedAttribute(String name) {
        return check(TokenType.IDENTIFIER)
                && name.equals(peek().getValue())
                && tokens.size() > current + 1
                && tokens.get(current + 1).getType() == TokenType.COLON;
    }

    private String parseImport() {
        Token pathToken =
                consume(TokenType.STRING_LITERAL, "Expect string literal for import path.");
        return pathToken.getValue();
    }

    private static final java.util.List<String> AGENT_SETTINGS =
            java.util.List.of(
                    "model",
                    "system",
                    "system_template",
                    "prompt",
                    "persona",
                    "tools",
                    "mcp_servers",
                    "skills",
                    "memory",
                    "voice",
                    "guard",
                    "routing",
                    "knowledge",
                    "output_schema",
                    "approve",
                    "max_iterations",
                    "temperature",
                    "budget",
                    "ladder",
                    "ceiling",
                    "context",
                    "streaming",
                    "reasoning",
                    "steerable",
                    "grounding",
                    "cache",
                    "shared_prompt");

    /**
     * What to say when something that is not a setting turns up inside an agent: the word itself,
     * what an agent can have, and the nearest guess.
     */
    private static String unknownAgentSetting(Token t) {
        String all = String.join(", ", AGENT_SETTINGS);
        if (t.getType() != TokenType.IDENTIFIER) {
            return "Unexpected "
                    + (t.getValue() == null || t.getValue().isBlank()
                            ? t.getType().toString()
                            : "\"" + t.getValue() + "\"")
                    + " inside an agent; an agent can have: "
                    + all
                    + ".";
        }
        java.util.List<String> near =
                io.github.llm4j.loom.prompt.PromptCatalog.nearest(t.getValue(), AGENT_SETTINGS);
        return "\""
                + t.getValue()
                + "\" is not something an agent can have. An agent can have: "
                + all
                + "."
                + (near.isEmpty() ? "" : " Did you mean " + String.join(" or ", near) + "?");
    }

    private AgentDef parseAgent() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect agent name.");
        AgentDef agent = new AgentDef(nameToken.getValue());
        agent.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before agent body.");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.MODEL)) {
                consume(TokenType.COLON, "Expect ':' after model.");
                Token modelToken =
                        consume(TokenType.STRING_LITERAL, "Expect string literal for model.");
                agent.setModel(modelToken.getValue());
            } else if (match(TokenType.SYSTEM)) {
                consume(TokenType.COLON, "Expect ':' after system.");
                Token sysToken =
                        consume(
                                TokenType.STRING_LITERAL,
                                "Expect string literal for system prompt.");
                agent.setSystemPrompt(sysToken.getValue());
            } else if (match(TokenType.SYSTEM_TEMPLATE)) {
                consume(TokenType.COLON, "Expect ':' after system_template.");
                Token tmpl =
                        consume(
                                TokenType.STRING_LITERAL,
                                "Expect string literal for system_template id.");
                agent.setSystemTemplate(tmpl.getValue());
            } else if (isNamedAttribute("prompt")) {
                Token keyword = advance();
                advance(); // the colon
                Token ref =
                        consume(
                                TokenType.STRING_LITERAL,
                                "Expect a string for prompt, e.g. prompt: \"researcher\" or prompt: \"researcher@v2\".");
                try {
                    io.github.llm4j.loom.prompt.PromptRef.parse(ref.getValue());
                } catch (IllegalArgumentException e) {
                    throw error(keyword, e.getMessage());
                }
                agent.setPrompt(ref.getValue());
            } else if (match(TokenType.PERSONA)) {
                consume(TokenType.COLON, "Expect ':' after persona.");
                Token personaToken =
                        check(TokenType.IDENTIFIER)
                                ? advance()
                                : consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a persona name, e.g. persona: Mentor");
                agent.setPersona(personaToken.getValue());
            } else if (match(TokenType.TOOLS)) {
                consume(TokenType.COLON, "Expect ':' after tools.");
                consume(TokenType.LBRACKET, "Expect '[' before tools list.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        Token toolToken = consume(TokenType.IDENTIFIER, "Expect tool name.");
                        agent.addTool(toolToken.getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after tools list.");
            } else if (match(TokenType.MCP_SERVERS)) {
                consume(TokenType.COLON, "Expect ':' after mcp_servers.");
                consume(TokenType.LBRACKET, "Expect '[' before mcp_servers list.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        Token srv = consume(TokenType.IDENTIFIER, "Expect MCP server name.");
                        agent.addMcpServer(srv.getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after mcp_servers list.");
            } else if (match(TokenType.SKILLS)) {
                consume(TokenType.COLON, "Expect ':' after skills.");
                consume(TokenType.LBRACKET, "Expect '[' before skills list.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        Token skillToken =
                                consume(TokenType.STRING_LITERAL, "Expect skill URI (string).");
                        agent.addSkill(skillToken.getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after skills list.");
            } else if (isNamedAttribute("shared_prompt")) {
                Token keyword = advance();
                advance(); // the colon
                Token value = advance();
                if (!"none".equals(value.getValue()))
                    throw error(
                            keyword,
                            "an agent's shared_prompt can only be none (shared_prompt: none leaves it out of the script's shared prompt); an agent's own words go in system:");
                agent.setSharedPromptExcluded(true);
            } else if (match(TokenType.MEMORY)) {
                int memoryLine = previous().getLine();
                match(TokenType.COLON);
                agent.setMemory(parseSettings(new AgentDef.MemoryConfig(), "memory", memoryLine));
            } else if (isSettingsBlock("voice")) {
                int line = advance().getLine();
                match(TokenType.COLON);
                agent.setVoice(parseSettings(new AgentDef.VoiceConfig(), "voice", line));
            } else if (isSettingsBlock("context")) {
                int line = advance().getLine();
                match(TokenType.COLON);
                agent.setContext(parseContextBlock(line));
            } else if (isSettingsBlock("guard")) {
                int line = advance().getLine();
                match(TokenType.COLON);
                agent.setGuard(parseGuard(line));
            } else if (match(TokenType.ROUTING)) {
                consume(TokenType.COLON, "Expect ':' after routing.");
                Token policyToken = consume(TokenType.IDENTIFIER, "Expect routing policy name.");
                agent.setRoutingPolicy(policyToken.getValue());
            } else if (match(TokenType.KNOWLEDGE)) {
                consume(TokenType.COLON, "Expect ':' after knowledge.");
                consume(TokenType.LBRACKET, "Expect '[' before knowledge list.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        Token kbToken =
                                consume(TokenType.IDENTIFIER, "Expect knowledge base name.");
                        agent.addKnowledgeBase(kbToken.getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after knowledge list.");
            } else if (match(TokenType.OUTPUT_SCHEMA)) {
                consume(TokenType.COLON, "Expect ':' after output_schema.");
                agent.setOutputSchema(parseSchema());
            } else if (check(TokenType.IDENTIFIER) && "approve".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after approve.");
                if (check(TokenType.IDENTIFIER) && "all".equals(peek().getValue())) {
                    advance();
                    agent.setApproveAll(true);
                } else {
                    consume(TokenType.LBRACKET, "Expect [Tool, …] or all after approve:");
                    if (!check(TokenType.RBRACKET)) {
                        do {
                            agent.getApprove()
                                    .add(
                                            consume(TokenType.IDENTIFIER, "Expect a tool name.")
                                                    .getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after approve list.");
                }
            } else if (check(TokenType.IDENTIFIER) && "max_iterations".equals(peek().getValue())) {
                Token keyword = advance();
                consume(TokenType.COLON, "Expect ':' after max_iterations.");
                Token n =
                        consume(
                                TokenType.NUMBER_LITERAL,
                                "Expect a whole number for max_iterations.");
                double v = Double.parseDouble(n.getValue());
                if (v < 1 || v != Math.floor(v)) {
                    throw error(
                            keyword,
                            "max_iterations must be a positive whole number, got " + n.getValue());
                }
                agent.setMaxIterations((int) v);
            } else if (check(TokenType.IDENTIFIER) && "temperature".equals(peek().getValue())) {
                // Contextual keyword, so existing scripts may still use "temperature" as a name
                // elsewhere.
                Token keyword = advance();
                consume(TokenType.COLON, "Expect ':' after temperature.");
                Token value =
                        consume(
                                TokenType.NUMBER_LITERAL,
                                "Expect a number for temperature, e.g. temperature: 0.7");
                double temperature = Double.parseDouble(value.getValue());
                if (temperature < 0.0 || temperature > 2.0) {
                    throw error(
                            keyword,
                            "temperature must be between 0.0 and 2.0, got " + value.getValue());
                }
                agent.setTemperature(temperature);
            } else if (isNamedAttribute("streaming")
                    || isNamedAttribute("reasoning")
                    || isNamedAttribute("steerable")
                    || isNamedAttribute("cache")) {
                Token keyword = advance();
                advance(); // the colon
                Token value = advance();
                if (!"true".equals(value.getValue()) && !"false".equals(value.getValue())) {
                    throw error(value, keyword.getValue() + " must be true or false.");
                }
                boolean on = "true".equals(value.getValue());
                switch (keyword.getValue()) {
                    case "streaming" -> agent.setStreaming(on);
                    case "reasoning" -> agent.setReasoning(on);
                    case "cache" -> agent.setPromptCaching(on);
                    default -> agent.setSteerable(on);
                }
            } else if (isBudgetKeyword()) {
                Token keyword = advance();
                if (agent.getBudget() != null)
                    throw error(keyword, "An agent can have only one budget block.");
                agent.setBudget(parseBudgetBlock(true));
            } else if (isNamedAttribute("grounding")) {
                Token keyword = advance();
                advance(); // the colon
                if (agent.getGrounding() != null)
                    throw error(keyword, "An agent can have only one grounding.");
                agent.setGrounding(parseGrounding(keyword.getLine()));
            } else if (isNamedAttribute("ladder")) {
                Token keyword = advance();
                advance(); // the colon
                if (agent.getLadder() != null)
                    throw error(keyword, "An agent can have only one default ladder.");
                agent.setLadder(parseLadderName(), keyword.getLine());
                parseLadderArgs(agent.getLadder(), agent.getLadderArgs());
            } else if (isNamedAttribute("ceiling")) {
                Token keyword = advance();
                advance(); // the colon
                agent.setCeilingRung(
                        rungName(
                                "ceiling:",
                                "Expect the name of a rung after \"ceiling:\", such as ceiling: suggest."),
                        keyword.getLine());
            } else {
                throw error(peek(), unknownAgentSetting(peek()));
            }
        }

        consume(TokenType.RBRACE, "Expect '}' after agent body.");
        return agent;
    }

    private WorkflowDef parseWorkflow() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect workflow name.");
        WorkflowDef workflow = new WorkflowDef(nameToken.getValue());
        workflow.setLine(nameToken.getLine());

        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    Token param = consume(TokenType.IDENTIFIER, "Expect parameter name.");
                    workflow.addParameter(param.getValue());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "Expect ')' after parameters.");
            // contextual (D17): `progress` is a keyword only here, right after ')' and before '{'
            if (isWord("progress") && peekAt(1).getType() == TokenType.LBRACE)
                workflow.setProgress(parseProgressDecl());
        }

        consume(TokenType.LBRACE, "Expect '{' before workflow body.");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            workflow.addStatement(parseStatement());
        }

        consume(TokenType.RBRACE, "Expect '}' after workflow body.");
        return workflow;
    }

    /** {@code progress { field: type, ... }}: one flat level of scalar types (D6). */
    private io.github.llm4j.loom.ast.ProgressDecl parseProgressDecl() {
        Token word = advance();
        consume(TokenType.LBRACE, "Expect '{' after progress.");
        java.util.Map<String, SchemaDef> fields = new java.util.LinkedHashMap<>();
        if (check(TokenType.RBRACE))
            throw error(peek(), "progress { } needs at least one field, such as progress { stage: string, done: number }.");
        do {
            Token field = advance();
            if (field.getValue() == null || !field.getValue().matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw error(field, "Expect a progress field name.");
            if (fields.containsKey(field.getValue()))
                throw error(field, "The progress field " + field.getValue() + " is declared twice.");
            consume(TokenType.COLON, "Expect ':' after the progress field " + field.getValue() + ".");
            if (check(TokenType.LBRACE))
                throw error(peek(), "progress is one flat level: " + field.getValue() + " cannot hold an object. Use string, number, boolean or enum[...].");
            SchemaDef type = parseSchema();
            if (type.getType() == SchemaDef.Type.LIST || type.getType() == SchemaDef.Type.OBJECT)
                throw error(
                        previous(),
                        "progress is one flat level: the field " + field.getValue() + " cannot be a list or an object. Use string, number, boolean or enum[...].");
            fields.put(field.getValue(), type);
        } while (match(TokenType.COMMA));
        consume(TokenType.RBRACE, "Expect '}' after the progress fields.");
        return new io.github.llm4j.loom.ast.ProgressDecl(fields, word.getLine());
    }

    /** {@code report { field: value, ... }}: flat values only. */
    private io.github.llm4j.loom.ast.ReportStmt parseReport() {
        advance();
        io.github.llm4j.loom.ast.ValueExpr value = parseValue();
        io.github.llm4j.loom.ast.ValueExpr.Obj obj = (io.github.llm4j.loom.ast.ValueExpr.Obj) value;
        for (var e : obj.fields().entrySet())
            if (e.getValue() instanceof io.github.llm4j.loom.ast.ValueExpr.Obj)
                throw error(previous(), "report is one flat level: " + e.getKey() + " cannot hold an object. Give a text, a number, true or false, or a variable.");
        return new io.github.llm4j.loom.ast.ReportStmt(obj);
    }

    /** Parses one statement and records the line its first token is on. */
    private Statement parseStatement() {
        int line = peek().getLine();
        Statement statement = parseStatementBody();
        statement.setLine(line);
        return statement;
    }

    private Statement parseStatementBody() {
        if (match(TokenType.NOTE)) {
            return parseNoteStmt();
        } else if (match(TokenType.HANDOFF)) {
            return parseHandoffStmt();
        } else if (match(TokenType.DELEGATE)) {
            return parseDelegateStmt();
        } else if (match(TokenType.ALT)) {
            return parseAltStmt();
        } else if (match(TokenType.BROADCAST)) {
            return parseBroadcastStmt();
        } else if (match(TokenType.LOOP)) {
            return parseLoopStmt();
        } else if (match(TokenType.HUMAN_PROMPT)) {
            return parseHumanPromptStmt();
        } else if (match(TokenType.GUARDRAIL)) {
            return parseGuardrailStatement();
        } else if (match(TokenType.PARALLEL)) {
            if (check(TokenType.IDENTIFIER) && "for".equals(peek().getValue())) {
                advance();
                return parseForEach(true);
            }
            return parseParallelStatement();
        } else if (check(TokenType.IDENTIFIER) && "for".equals(peek().getValue())) {
            advance();
            return parseForEach(false);
        } else if (match(TokenType.OBSERVE)) {
            return parseObserveStatement();
        } else if (match(TokenType.CALL)) {
            return parseCallStmt();
        } else if (parseOutcomeAhead()) {
            return pendingOutcome;
        } else if (isNotifyStatement()) {
            return parseNotify();
        } else if (isSignalWaitStatement()) {
            return parseSignalWait();
        } else if (isWord("report") && peekAt(1).getType() == TokenType.LBRACE) {
            return parseReport(); // contextual (D17): only `report {` at the start of a statement
        } else if (isWord("checkpoint") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseCheckpoint();
        } else if (isWord("rewind") && peekAt(1).getType() == TokenType.TO) {
            return parseRewind();
        } else if (isWord("decide") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseDecide();
        } else if (isWord("intentclassify") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseIntentClassify();
        } else if (isWord("extract")
                && peekAt(1).getType() == TokenType.IDENTIFIER
                && "from".equals(peekAt(2).getValue())) {
            return parseExtract();
        } else if (isWord("check")
                && peekAt(1).getValue() != null
                && peekAt(1).getValue().matches("[A-Za-z_][A-Za-z0-9_.]*")
                && "against".equals(peekAt(2).getValue())) {
            return parseCheck();
        } else if (isStartStatement()) {
            return parseStart();
        } else if (isWaitStatement()) {
            return parseWait();
        } else if (isCancelStatement()) {
            return parseCancel();
        } else if (isSteerStatement()) {
            return parseSteer();
        } else if (isSuperviseStatement()) {
            return parseSuperviseStatement();
        } else if (isWord("halt")) {
            return parseHalt();
        } else if (isWord("set")) {
            return parseSet();
        } else if (isWord("return")) {
            return parseReturn();
        } else if (isRunStart()) {
            return parseRun();
        } else if (isWord("use")
                && isNameToken(peekAt(1))
                && peekAt(2).getType() == TokenType.LPAREN) {
            return parseUse();
        } else if (isWord("sleep") && isDurationStart(peekAt(1))) {
            return parseSleep();
        } else if (isWord("ask") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseAsk();
        }

        if (isWord("attach")) {
            throw error(
                    peek(),
                    "attach belongs after the target of a delegate, broadcast, handoff and extract,"
                            + " as in: delegate \"What is the total?\" to Reader attach image"
                            + " \"receipt.png\" -> total. note and halt send nothing to a model, so"
                            + " they take no attachment.");
        }
        throw error(peek(), "Expected statement, got " + peek().getType());
    }

    // -----------------------------------------------------------------------
    // Checkpoints and rewinds: read as sentences, in a fixed order of phrases.
    // -----------------------------------------------------------------------

    /**
     * True when the next token is the plain word {@code word} (these words are keywords only where
     * a statement starts).
     */
    private OutcomeStmt pendingOutcome;

    private boolean parseOutcomeAhead() {
        pendingOutcome = parseOutcome();
        return pendingOutcome != null;
    }

    /** {@code media { max_bytes: 20MB  max_items: 4 }}, after the word {@code media}. */
    private void parseMediaBlock(LoomScript script) {
        consume(TokenType.LBRACE, "Expect '{' after media.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = consume(TokenType.IDENTIFIER, "Expect max_bytes or max_items in media { }.");
            if (!"max_bytes".equals(key.getValue()) && !"max_items".equals(key.getValue())) {
                throw error(
                        key, "media { } takes max_bytes and max_items, not " + key.getValue() + ".");
            }
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            Token number = consume(TokenType.NUMBER_LITERAL, "Expect a number after " + key.getValue() + ":.");
            if ("max_bytes".equals(key.getValue())) script.setMediaMaxBytes(bytesWithUnit(number));
            else script.setMediaMaxItems((int) Double.parseDouble(number.getValue()));
        }
        consume(TokenType.RBRACE, "Expect '}' after the media settings.");
    }

    /** A byte count: a number, then KB, MB or GB, or nothing for bytes. */
    private long bytesWithUnit(Token number) {
        double value = Double.parseDouble(number.getValue());
        long factor = 1;
        // a word that is followed by a colon is the next setting, not a unit
        if (check(TokenType.IDENTIFIER) && peekAt(1).getType() != TokenType.COLON) {
            Token unit = advance();
            factor =
                    switch (unit.getValue().toUpperCase(java.util.Locale.ROOT)) {
                        case "B" -> 1L;
                        case "KB" -> 1024L;
                        case "MB" -> 1024L * 1024;
                        case "GB" -> 1024L * 1024 * 1024;
                        default -> throw error(
                                unit,
                                "max_bytes takes a number and then KB, MB or GB, not "
                                        + unit.getValue()
                                        + ".");
                    };
        }
        return (long) (value * factor);
    }

    private boolean isWord(String word) {
        return check(TokenType.IDENTIFIER) && word.equals(peek().getValue());
    }

    private Token peekAt(int offset) {
        int i = Math.min(current + offset, tokens.size() - 1);
        return tokens.get(i);
    }

    private void expectWord(String word, String message) {
        if (!isWord(word)) throw error(peek(), message);
        advance();
    }

    // -----------------------------------------------------------------------
    // run: a deterministic task (plain Java, no model).
    // -----------------------------------------------------------------------

    /**
     * A word that can name a task or an argument: it looks like an identifier, whichever keyword
     * the lexer made of it.
     */
    private static boolean isNameToken(Token t) {
        return t.getType() != TokenType.STRING_LITERAL
                && t.getType() != TokenType.NUMBER_LITERAL
                && t.getValue() != null
                && t.getValue().matches("[A-Za-z][A-Za-z0-9_-]*");
    }

    /**
     * {@code run Name(}: `run` is a keyword only where a statement starts and a task name and '('
     * follow, so it stays a usable variable name.
     */
    private boolean isRunStart() {
        return isWord("run") && isNameToken(peekAt(1)) && peekAt(2).getType() == TokenType.LPAREN;
    }

    /**
     * {@code run Task(name = value, ...) [model "name"] -> variable [model "name"] [retry N]
     * [backoff 2s] [timeout 30s] [on_failure { ... }]}. A value is a variable path (passed with its
     * type), a quoted string (placeholders filled in), a number or true/false.
     */
    private RunStmt parseRun() {
        Token keyword = advance();
        Token task = advance();
        consume(
                TokenType.LPAREN,
                "Expect '(' after the task name, as in run " + task.getValue() + "(name = value).");
        List<RunStmt.Arg> args = parseCallArgs("run", task);
        String model =
                null; // model "name" may come before the arrow, as it reads best, or with the other
        // options after the variable
        if (match(TokenType.MODEL)) {
            match(TokenType.COLON);
            model =
                    consume(
                                    TokenType.STRING_LITERAL,
                                    "Expect a quoted model name, as in model \"gemini-2.5-flash\".")
                            .getValue();
        }
        boolean approve = false;
        String heading = null;
        if (isWord("approve")) {
            advance();
            approve = true;
            if (check(TokenType.STRING_LITERAL)) heading = advance().getValue();
        }
        consume(
                TokenType.ARROW,
                "Expect '->' and a variable to hold the result, as in run "
                        + task.getValue()
                        + "(...) -> result.");
        String variable =
                nameOrReference("Expect a variable name for the result (or {item.field}).");

        RunStmt stmt = new RunStmt(task.getValue(), variable);
        stmt.setLine(keyword.getLine());
        stmt.setModel(model);
        stmt.setApprove(approve);
        stmt.setApproveHeading(heading);
        stmt.getArgs().addAll(args);
        // Optional, in any order: model "name" · retry 3 · backoff 2s · timeout 30s
        while (true) {
            if (match(TokenType.MODEL)) {
                match(TokenType.COLON); // model: "x" reads as it does in an agent
                if (stmt.getModel() != null)
                    throw error(previous(), "model is given twice in run " + task.getValue() + ".");
                stmt.setModel(
                        consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a quoted model name, as in model \"gemini-2.5-flash\".")
                                .getValue());
            } else if (match(TokenType.RETRY)) {
                Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
                stmt.setRetryCount((int) Double.parseDouble(count.getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue())
                            || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis);
                else stmt.setTimeoutMillis(millis);
            } else if (isBudgetModifier()) {
                throw error(
                        peek(),
                        "A task has no budget of its own: remove 'budget' from run "
                                + task.getValue()
                                + ". Its model calls count against the enclosing run, loop or step budgets.");
            } else {
                break;
            }
        }
        parseDeclined(stmt.getOnDeclined(), stmt.isApprove(), "run " + task.getValue());
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                stmt.getOnFailure().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    /** {@code on_declined { … }}: only on a step with {@code approve}. */
    private void parseDeclined(List<Statement> into, boolean approve, String what) {
        if (!isWord("on_declined")) return;
        Token at = advance();
        if (!approve)
            throw error(
                    at,
                    "on_declined is for a step a person approves: write approve in "
                            + what
                            + " first.");
        consume(TokenType.LBRACE, "Expect '{' before on_declined body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) into.add(parseStatement());
        consume(TokenType.RBRACE, "Expect '}' after on_declined body.");
    }

    /**
     * {@code use Tool(name = value) [approve ["heading"]] [-> var] [retry N] [backoff T] [timeout T]
     * [on_declined { … }] [on_failure { … }]}: a tool called with no model in between.
     */
    private UseStmt parseUse() {
        Token keyword = advance();
        Token tool = advance();
        consume(
                TokenType.LPAREN,
                "Expect '(' after the tool name, as in use " + tool.getValue() + "(name = value).");
        List<RunStmt.Arg> args = parseCallArgs("use", tool);
        boolean approve = false;
        String heading = null;
        if (isWord("approve")) {
            advance();
            approve = true;
            if (check(TokenType.STRING_LITERAL)) heading = advance().getValue();
        }
        String variable = null;
        if (match(TokenType.ARROW))
            variable = nameOrReference("Expect a variable name for the result.");
        UseStmt stmt = new UseStmt(tool.getValue(), variable);
        stmt.setLine(keyword.getLine());
        stmt.setApprove(approve);
        stmt.setApproveHeading(heading);
        stmt.getArgs().addAll(args);
        while (true) {
            if (match(TokenType.RETRY)) {
                stmt.setRetryCount(
                        (int)
                                Double.parseDouble(
                                        consume(TokenType.NUMBER_LITERAL, "Expect number of retries.")
                                                .getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue()) || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis);
                else stmt.setTimeoutMillis(millis);
            } else {
                break;
            }
        }
        parseDeclined(stmt.getOnDeclined(), approve, "use " + tool.getValue());
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) stmt.getOnFailure().add(parseStatement());
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    /** The {@code name = value, …} arguments of a {@code run} or {@code use}, up to the ')'. */
    private List<RunStmt.Arg> parseCallArgs(String verb, Token task) {
        List<RunStmt.Arg> args = new java.util.ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                Token key = advance();
                if (!isNameToken(key))
                    throw error(
                            key,
                            "Expect an argument name, as in " + verb + " "
                                    + task.getValue()
                                    + "(name = value).");
                consume(
                        TokenType.ASSIGN,
                        "Expect '=' after "
                                + key.getValue()
                                + ", as in "
                                + key.getValue()
                                + " = value.");
                for (RunStmt.Arg earlier : args) {
                    if (earlier.name().equals(key.getValue()))
                        throw error(key, "Argument " + key.getValue() + " is given twice.");
                }
                Token value = advance();
                if (value.getType() == TokenType.STRING_LITERAL) {
                    args.add(
                            new RunStmt.Arg(key.getValue(), RunStmt.Kind.STRING, value.getValue()));
                } else if (value.getType() == TokenType.NUMBER_LITERAL) {
                    args.add(
                            new RunStmt.Arg(key.getValue(), RunStmt.Kind.NUMBER, value.getValue()));
                } else if (isNameToken(value)
                        && ("true".equals(value.getValue()) || "false".equals(value.getValue()))) {
                    args.add(
                            new RunStmt.Arg(
                                    key.getValue(), RunStmt.Kind.BOOLEAN, value.getValue()));
                } else if (isNameToken(value)
                        || (value.getValue() != null
                                && value.getValue().matches("[A-Za-z][A-Za-z0-9_.-]*"))) {
                    args.add(
                            new RunStmt.Arg(
                                    key.getValue(), RunStmt.Kind.REFERENCE, value.getValue()));
                } else {
                    throw error(
                            value,
                            "Expect a value for "
                                    + key.getValue()
                                    + ": a variable (request.amount), a quoted string, a number or true/false.");
                }
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expect ')' after the arguments of " + task.getValue() + ".");
        return args;
    }

    /** {@code checkpoint Name [starting with name = "value", name = "value"]} */
    private CheckpointStmt parseCheckpoint() {
        Token keyword = advance();
        CheckpointStmt stmt =
                new CheckpointStmt(
                        consume(TokenType.IDENTIFIER, "Expect a name after checkpoint.")
                                .getValue());
        stmt.setLine(keyword.getLine());
        if (isWord("starting")) {
            advance();
            expectWord(
                    "with",
                    "Write: checkpoint " + stmt.getName() + "  starting with name = \"value\"");
            parseAssignments(stmt.getStartingWith(), "starting with");
        }
        return stmt;
    }

    /** name = "value" [, name = "value"]... */
    private void parseAssignments(java.util.Map<String, String> into, String after) {
        do {
            Token name =
                    consume(TokenType.IDENTIFIER, "Expect a variable name after '" + after + "'.");
            consume(
                    TokenType.ASSIGN,
                    "Expect '=' after "
                            + name.getValue()
                            + ", as in "
                            + name.getValue()
                            + " = \"value\".");
            if (match(TokenType.STRING_LITERAL, TokenType.NUMBER_LITERAL, TokenType.IDENTIFIER)) {
                into.put(name.getValue(), previous().getValue());
            } else {
                throw error(peek(), "Expect a value (in quotes) for " + name.getValue() + ".");
            }
        } while (match(TokenType.COMMA));
    }

    /**
     * {@code rewind to Name [when (condition)] at most N times [carrying a = "x", ...] [side
     * effects: ask first|keep|repeat] [if it still fails { ... }] [if blocked { ... }]}
     */
    private RewindStmt parseRewind() {
        Token keyword = advance();
        consume(TokenType.TO, "Expect 'to' after rewind.");
        Token target =
                consume(TokenType.IDENTIFIER, "Expect the name of a checkpoint after 'rewind to'.");

        String condition = null;
        if (isWord("when")) {
            advance();
            consume(
                    TokenType.LPAREN,
                    "Write the condition in brackets: rewind to "
                            + target.getValue()
                            + " when (score < 7)");
            condition = readCondition();
            consume(TokenType.RPAREN, "Expect ')' after the rewind condition.");
        }

        if (!isWord("at"))
            throw error(
                    peek(), "A rewind needs a limit: add \"at most 2 times\" (or another number).");
        advance();
        expectWord("most", "Write the limit as: at most 2 times");
        Token n =
                consume(
                        TokenType.NUMBER_LITERAL,
                        "Expect a number after 'at most', as in: at most 2 times");
        int atMost = (int) Double.parseDouble(n.getValue());
        if (atMost < 1) throw error(n, "\"at most\" must be at least 1 time");
        if (isWord("times") || isWord("time")) advance();
        else throw error(peek(), "Write the limit as: at most " + atMost + " times");

        RewindStmt stmt = new RewindStmt(target.getValue(), condition, atMost);
        stmt.setLine(keyword.getLine());

        if (isWord("carrying")) {
            advance();
            parseAssignments(stmt.getCarrying(), "carrying");
        }
        if (isWord("side")) {
            Token side = advance();
            expectWord("effects", "Write: side effects: ask first | keep | repeat");
            consume(TokenType.COLON, "Expect ':' after 'side effects'.");
            String phrase = check(TokenType.IDENTIFIER) ? advance().getValue() : "";
            if (phrase.equals("ask") && isWord("first")) {
                advance();
                phrase = "ask first";
            }
            RewindStmt.Effects effects = RewindStmt.Effects.of(phrase);
            if (effects == null)
                throw error(
                        side,
                        "Side effects can be: ask first, keep or repeat (not \"" + phrase + "\").");
            stmt.setEffects(effects);
        }
        while (isWord("if")) {
            Token ifToken = advance();
            java.util.List<Statement> into;
            if (isWord("blocked")) {
                advance();
                into = stmt.getIfBlocked();
            } else if (isWord("it")) {
                advance();
                expectWord("still", "Write: if it still fails { ... }");
                expectWord("fails", "Write: if it still fails { ... }");
                into = stmt.getIfStillFails();
            } else {
                throw error(
                        ifToken,
                        "After a rewind, write either: if it still fails { ... } or: if blocked { ... }");
            }
            consume(TokenType.LBRACE, "Expect '{' before the handler.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) into.add(parseStatement());
            consume(TokenType.RBRACE, "Expect '}' after the handler.");
        }
        return stmt;
    }

    // -----------------------------------------------------------------------
    // Decisions and earned autonomy: plain phrases in a fixed order, read aloud.
    // -----------------------------------------------------------------------

    /**
     * The next token is the plain word {@code word}, whatever kind of token the lexer made of it
     * ("agent" and "to" are keywords elsewhere).
     */
    private boolean wordIs(String word) {
        return peek().getType() != TokenType.STRING_LITERAL
                && peek().getType() != TokenType.EOF
                && word.equals(peek().getValue());
    }

    private void word(String word, String hint) {
        if (!wordIs(word)) throw error(peek(), hint);
        advance();
    }

    private boolean isDecisionStart() {
        return isWord("decision")
                && peekAt(1).getType() == TokenType.IDENTIFIER
                && peekAt(2).getType() == TokenType.LBRACE;
    }

    /** {@code decide Name -> variable} */
    private io.github.llm4j.loom.ast.DecideStmt parseDecide() {
        Token keyword = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect the name of a decision after decide.");
        consume(
                TokenType.ARROW,
                "Write: decide "
                        + name.getValue()
                        + " -> verdict (the name the answer is kept under)");
        Token variable = consume(TokenType.IDENTIFIER, "Expect a variable name after '->'.");
        io.github.llm4j.loom.ast.DecideStmt stmt =
                new io.github.llm4j.loom.ast.DecideStmt(name.getValue(), variable.getValue());
        stmt.setLine(keyword.getLine());
        return stmt;
    }

    private int whole(String what) {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a whole number for " + what + ".");
        double d = Double.parseDouble(n.getValue());
        if (d != Math.rint(d))
            throw error(n, what + " must be a whole number, not " + n.getValue());
        return (int) d;
    }

    private double percent(String what) {
        Token n =
                consume(
                        TokenType.NUMBER_LITERAL,
                        "Expect a percentage for " + what + ", such as 90%.");
        consume(
                TokenType.PERCENT,
                "Write " + what + " as a percentage, such as 90% (not " + n.getValue() + ").");
        return Double.parseDouble(n.getValue());
    }

    private io.github.llm4j.loom.autonomy.Level level(String after) {
        Token t = advance();
        io.github.llm4j.loom.autonomy.Level l =
                io.github.llm4j.loom.autonomy.Level.of(t.getValue());
        if (l == null || t.getType() == TokenType.STRING_LITERAL)
            throw error(
                    t,
                    "After \""
                            + after
                            + "\" write watch, suggest or act (not \""
                            + t.getValue()
                            + "\").");
        return l;
    }

    private void days(String what) {
        if (wordIs("days") || wordIs("day")) advance();
        else throw error(peek(), "Write " + what + " in days, such as 14 days.");
    }

    private void cases() {
        if (wordIs("cases") || wordIs("case")) advance();
        else throw error(peek(), "Expect the word \"cases\" here.");
    }

    /** The rest of the line, as written (a condition such as {@code amount > 200}). */
    private String restOfLine() {
        int line = peek().getLine();
        StringBuilder b = new StringBuilder();
        while (!isAtEnd() && peek().getLine() == line && !check(TokenType.RBRACE)) {
            Token t = advance();
            if (b.length() > 0 && t.getType() != TokenType.PERCENT) b.append(' ');
            b.append(
                    t.getType() == TokenType.STRING_LITERAL
                            ? "\"" + t.getValue() + "\""
                            : t.getValue());
        }
        return b.toString();
    }

    private io.github.llm4j.loom.ast.DecisionDef parseDecision() {
        Token keyword = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect a decision name.");
        io.github.llm4j.loom.ast.DecisionDef d =
                new io.github.llm4j.loom.ast.DecisionDef(name.getValue());
        d.setLine(keyword.getLine());
        consume(TokenType.LBRACE, "Expect '{' before the decision body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token first = peek();
            if (wordIs("proposed")) {
                advance();
                word("by", "Write: proposed by: AgentName");
                consume(TokenType.COLON, "Expect ':' after \"proposed by\".");
                d.setAgent(
                        consume(TokenType.IDENTIFIER, "Expect the name of the agent that proposes.")
                                .getValue());
            } else if (wordIs("choices")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"choices\".");
                do
                    d.getChoices()
                            .add(
                                    consume(
                                                    TokenType.IDENTIFIER,
                                                    "Expect a choice name, such as approve.")
                                            .getValue());
                while (match(TokenType.COMMA));
            } else if (wordIs("group")) {
                advance();
                word("cases", "Write: group cases by: variable");
                word("by", "Write: group cases by: variable");
                consume(TokenType.COLON, "Expect ':' after \"group cases by\".");
                d.setGroupBy(
                        consume(
                                        TokenType.IDENTIFIER,
                                        "Expect a variable name after \"group cases by:\".")
                                .getValue());
            } else if (wordIs("remember")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"remember\".");
                do {
                    Token variable =
                            consume(TokenType.IDENTIFIER, "Expect a variable name to remember.");
                    d.getRemember().add(variable.getValue());
                    if (match(TokenType.COLON)) {
                        Token type = advance();
                        if (type.getType() == TokenType.STRING_LITERAL
                                || !java.util.Set.of("number", "text", "flag")
                                        .contains(type.getValue())) {
                            throw error(
                                    type,
                                    "After \""
                                            + variable.getValue()
                                            + ":\" write number, text or flag (not \""
                                            + type.getValue()
                                            + "\").");
                        }
                        d.getRememberTypes().put(variable.getValue(), type.getValue());
                    }
                } while (match(TokenType.COMMA));
            } else if (wordIs("dangerous")) {
                advance();
                word("mistake", "Write: dangerous mistake: propose approve, person decides reject");
                consume(TokenType.COLON, "Expect ':' after \"dangerous mistake\".");
                word("propose", "Write: dangerous mistake: propose approve, person decides reject");
                String proposed =
                        consume(TokenType.IDENTIFIER, "Expect the choice the agent proposes.")
                                .getValue();
                consume(
                        TokenType.COMMA,
                        "Write: dangerous mistake: propose "
                                + proposed
                                + ", person decides reject");
                word(
                        "person",
                        "Write: dangerous mistake: propose "
                                + proposed
                                + ", person decides reject");
                word(
                        "decides",
                        "Write: dangerous mistake: propose "
                                + proposed
                                + ", person decides reject");
                d.getDangerous()
                        .add(
                                new io.github.llm4j.loom.ast.DecisionDef.Mistake(
                                        proposed,
                                        consume(
                                                        TokenType.IDENTIFIER,
                                                        "Expect the choice the person makes.")
                                                .getValue()));
            } else if (wordIs("ask")) {
                advance();
                consume(
                        TokenType.COLON,
                        "Expect ':' after \"ask\" (who decides), as in: ask: support-lead");
                d.setAsk(
                        consume(TokenType.IDENTIFIER, "Expect the name of who decides.")
                                .getValue());
            } else if (wordIs("keep")) {
                advance();
                word("records", "Write: keep records for: 180 days");
                word("for", "Write: keep records for: 180 days");
                consume(TokenType.COLON, "Expect ':' after \"keep records for\".");
                d.setKeepDays(whole("the days to keep records"));
                days("how long to keep records");
            } else if (wordIs("when")) {
                advance();
                word(
                        "the",
                        "Write: when the agent changes: start over | test it on past cases | keep the trust");
                word(
                        "agent",
                        "Write: when the agent changes: start over | test it on past cases | keep the trust");
                word(
                        "changes",
                        "Write: when the agent changes: start over | test it on past cases | keep the trust");
                consume(TokenType.COLON, "Expect ':' after \"when the agent changes\".");
                if (wordIs("start")) {
                    advance();
                    word("over", "Write: start over");
                    d.setOnChange(io.github.llm4j.loom.ast.DecisionDef.OnChange.START_OVER);
                } else if (wordIs("test")) {
                    advance();
                    word("it", "Write: test it on past cases");
                    word("on", "Write: test it on past cases");
                    word("past", "Write: test it on past cases");
                    cases();
                    d.setOnChange(io.github.llm4j.loom.ast.DecisionDef.OnChange.TEST_ON_PAST);
                } else if (wordIs("keep")) {
                    advance();
                    word("the", "Write: keep the trust");
                    word("trust", "Write: keep the trust");
                    d.setOnChange(io.github.llm4j.loom.ast.DecisionDef.OnChange.KEEP_TRUST);
                } else {
                    throw error(
                            peek(),
                            "After \"when the agent changes:\" write start over, test it on past cases or keep the trust.");
                }
            } else if (wordIs("tell")) {
                advance();
                d.setTellTool(
                        consume(
                                        TokenType.IDENTIFIER,
                                        "Expect the name of a tool to tell, as in: tell Slack when trust changes")
                                .getValue());
                word("when", "Write: tell " + d.getTellTool() + " when trust changes");
                word("trust", "Write: tell " + d.getTellTool() + " when trust changes");
                word("changes", "Write: tell " + d.getTellTool() + " when trust changes");
            } else if (wordIs("task")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"task\".");
                d.setTask(
                        consume(TokenType.STRING_LITERAL, "Expect the task text in quotes.")
                                .getValue());
            } else if (wordIs("flag")) {
                advance();
                word("cases", "Write: flag cases with no verdict after 7 days");
                word("with", "Write: flag cases with no verdict after 7 days");
                word("no", "Write: flag cases with no verdict after 7 days");
                word("verdict", "Write: flag cases with no verdict after 7 days");
                word("after", "Write: flag cases with no verdict after 7 days");
                d.setStaleDays(whole("the days before a case is flagged"));
                days("how long before a case is flagged");
            } else if (wordIs("trust") && peekAt(1).getType() == TokenType.LBRACE) {
                advance();
                parseTrust(d);
            } else if (wordIs("ladder") && peekAt(1).getType() == TokenType.COLON) {
                advance();
                advance();
                parseLadderBinding(d);
            } else if (wordIs("ceiling") && peekAt(1).getType() == TokenType.COLON) {
                advance();
                advance();
                d.setCeilingRung(
                        rungName(
                                "ceiling:",
                                "Expect the name of a rung after \"ceiling:\", such as ceiling: suggest."),
                        first.getLine());
            } else if (wordIs("proposes") && peekAt(1).getType() == TokenType.COLON) {
                advance();
                advance();
                d.setProposes(
                        consume(
                                        TokenType.IDENTIFIER,
                                        "Expect the name of what the agent proposes after \"proposes:\", such as proposes: script.")
                                .getValue(),
                        first.getLine());
            } else {
                throw error(
                        first,
                        "Unexpected \""
                                + first.getValue()
                                + "\" in decision "
                                + d.getName()
                                + ". A decision holds: proposed by, choices, proposes, group cases by, remember, "
                                + "dangerous mistake, ask, keep records for, when the agent changes, tell, task, flag cases, ladder, ceiling, and a trust { } block.");
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after the decision body.");
        return d;
    }

    /** A rung or ladder name: any word, since the lexer makes keywords of some ordinary ones. */
    private String rungName(String after, String hint) {
        Token t = peek();
        if (t.getType() == TokenType.STRING_LITERAL
                || !t.getValue().matches("[A-Za-z_][A-Za-z0-9_-]*")) throw error(t, hint);
        advance();
        return t.getValue();
    }

    /** After {@code ladder:} : {@code Name} or {@code Name(param: value, …)}. */
    private void parseLadderBinding(io.github.llm4j.loom.ast.DecisionDef d) {
        Token at = peek();
        d.setLadder(parseLadderName(), at.getLine());
        parseLadderArgs(d.getLadder(), d.getLadderArgs());
    }

    private String parseLadderName() {
        return rungName(
                "ladder:",
                "Expect the name of a ladder after \"ladder:\", such as ladder: RefundLadder.");
    }

    private void parseLadderArgs(
            String name, java.util.Map<String, io.github.llm4j.loom.autonomy.expr.Expr> into) {
        if (!match(TokenType.LPAREN)) return;
        if (!check(TokenType.RPAREN)) {
            do {
                Token key =
                        consume(
                                TokenType.IDENTIFIER,
                                "Expect a parameter name in the arguments of ladder " + name + ".");
                consume(
                        TokenType.COLON,
                        "Write the arguments of a ladder as name: value, such as baseline_views: 1200.");
                StringBuilder text = new StringBuilder();
                int line = peek().getLine();
                while (!check(TokenType.COMMA) && !check(TokenType.RPAREN) && !isAtEnd()) {
                    Token t = advance();
                    text.append(
                            t.getType() == TokenType.STRING_LITERAL
                                    ? "\"" + t.getValue() + "\""
                                    : t.getValue());
                }
                if (text.length() == 0)
                    throw error(peek(), "Expect a value after \"" + key.getValue() + ":\".");
                try {
                    into.put(
                            key.getValue(),
                            io.github.llm4j.loom.autonomy.expr.ExprParser.parse(text.toString()));
                } catch (io.github.llm4j.loom.autonomy.expr.ExprException e) {
                    throw new RuntimeException(
                            "Parse error at line "
                                    + line
                                    + ": "
                                    + e.getMessage()
                                    + " (in the argument "
                                    + key.getValue()
                                    + ")");
                }
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expect ')' to close the arguments of ladder " + name + ".");
    }

    private void parseTrust(io.github.llm4j.loom.ast.DecisionDef d) {
        d.setTrustGiven(true);
        consume(TokenType.LBRACE, "Expect '{' after trust.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token first = peek();
            if (wordIs("start")) {
                advance();
                word("at", "Write: start at watch");
                d.setStartAt(level("start at"));
            } else if (wordIs("never")) {
                advance();
                word("go", "Write: never go above suggest");
                word("above", "Write: never go above suggest");
                d.setCeiling(level("never go above"));
            } else if (first.getType() == TokenType.TO) {
                advance();
                io.github.llm4j.loom.autonomy.Level to = level("to");
                consume(TokenType.COLON, "Expect ':' after \"to " + to.word() + "\".");
                if (!wordIs("after"))
                    throw error(
                            peek(),
                            "\"to "
                                    + to.word()
                                    + "\" needs \"after N cases\", as in: to "
                                    + to.word()
                                    + ": after 100 cases over 14 days, agreeing at least 90%");
                advance();
                int cases = whole("the number of cases");
                cases();
                int days = 0;
                if (wordIs("over")) {
                    advance();
                    days = whole("the number of days");
                    days("the time the cases must span");
                }
                consume(
                        TokenType.COMMA,
                        "Write: to "
                                + to.word()
                                + ": after "
                                + cases
                                + " cases over 14 days, agreeing at least 90%");
                word("agreeing", "\"to " + to.word() + "\" needs \"agreeing at least P%\"");
                word("at", "Write: agreeing at least 90%");
                word("least", "Write: agreeing at least 90%");
                double agree = percent("how much the agent must agree");
                boolean none = false;
                Double atMost = null;
                if (match(TokenType.COMMA)) {
                    word(
                            "with",
                            "Write: with no dangerous mistakes, or: with at most 1% dangerous mistakes");
                    if (wordIs("no")) {
                        advance();
                        none = true;
                    } else {
                        word(
                                "at",
                                "Write: with no dangerous mistakes, or: with at most 1% dangerous mistakes");
                        word("most", "Write: with at most 1% dangerous mistakes");
                        atMost = percent("the dangerous mistakes allowed");
                    }
                    word("dangerous", "Write: dangerous mistakes");
                    word("mistakes", "Write: dangerous mistakes");
                }
                if (d.getUpRules()
                                .put(
                                        to,
                                        new io.github.llm4j.loom.ast.DecisionDef.UpRule(
                                                to,
                                                cases,
                                                days,
                                                agree,
                                                none,
                                                atMost,
                                                first.getLine()))
                        != null) {
                    throw error(first, "There are two rules for moving up to " + to.word() + ".");
                }
            } else if (wordIs("judge")) {
                advance();
                word("on", "Write: judge on the latest 300 cases");
                word("the", "Write: judge on the latest 300 cases");
                word("latest", "Write: judge on the latest 300 cases");
                d.setWindow(whole("the number of cases to judge on"));
                cases();
            } else if (wordIs("check")) {
                advance();
                d.setAuditPercent(percent("the share of cases to check"));
                word("of", "Write: check 5% of cases with a person who doesn't see the proposal");
                cases();
                word("with", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("a", "Write: check 5% of cases with a person who doesn't see the proposal");
                word(
                        "person",
                        "Write: check 5% of cases with a person who doesn't see the proposal");
                word("who", "Write: check 5% of cases with a person who doesn't see the proposal");
                word(
                        "doesn't",
                        "Write: check 5% of cases with a person who doesn't see the proposal");
                word("see", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("the", "Write: check 5% of cases with a person who doesn't see the proposal");
                word(
                        "proposal",
                        "Write: check 5% of cases with a person who doesn't see the proposal");
            } else if (wordIs("always")) {
                advance();
                word("ask", "Write: always ask a person when amount > 200");
                word("a", "Write: always ask a person when amount > 200");
                word("person", "Write: always ask a person when amount > 200");
                if (wordIs("when")) {
                    advance();
                    String condition = restOfLine();
                    if (condition.isBlank())
                        throw error(first, "Say when: always ask a person when amount > 200");
                    d.getAskWhen().add(condition);
                } else if (wordIs("after")) {
                    advance();
                    d.setAskAfterPerDay(whole("the cases a day"));
                    cases();
                    word("a", "Write: always ask a person after 50 cases a day");
                    word("day", "Write: always ask a person after 50 cases a day");
                } else {
                    throw error(
                            peek(),
                            "Write: always ask a person when amount > 200, or: always ask a person after 50 cases a day");
                }
            } else if (wordIs("drop")) {
                advance();
                if (!check(TokenType.TO))
                    throw error(
                            peek(), "Write: drop to suggest when 2 dangerous mistakes in 50 cases");
                advance();
                io.github.llm4j.loom.autonomy.Level to = level("drop to");
                word(
                        "when",
                        "Write: drop to " + to.word() + " when 2 dangerous mistakes in 50 cases");
                io.github.llm4j.loom.ast.DecisionDef.DropRule rule;
                if (wordIs("agreement")) {
                    advance();
                    word(
                            "falls",
                            "Write: drop to " + to.word() + " when agreement falls below 92%");
                    word(
                            "below",
                            "Write: drop to " + to.word() + " when agreement falls below 92%");
                    rule =
                            new io.github.llm4j.loom.ast.DecisionDef.DropRule(
                                    to,
                                    io.github.llm4j.loom.ast.DecisionDef.Count.AGREEMENT_BELOW,
                                    0,
                                    0,
                                    percent("the floor"),
                                    first.getLine());
                } else {
                    int n = whole("how many");
                    io.github.llm4j.loom.ast.DecisionDef.Count count;
                    if (wordIs("dangerous")) {
                        advance();
                        word("mistakes", "Write: dangerous mistakes");
                        count = io.github.llm4j.loom.ast.DecisionDef.Count.DANGEROUS_MISTAKES;
                    } else if (wordIs("reversals") || wordIs("reversal")) {
                        advance();
                        count = io.github.llm4j.loom.ast.DecisionDef.Count.REVERSALS;
                    } else if (wordIs("unusable")) {
                        advance();
                        if (wordIs("proposals") || wordIs("proposal")) advance();
                        else throw error(peek(), "Write: unusable proposals");
                        count = io.github.llm4j.loom.ast.DecisionDef.Count.UNUSABLE_PROPOSALS;
                    } else {
                        throw error(
                                peek(),
                                "After a number write dangerous mistakes, reversals or unusable proposals, as in: drop to suggest when 2 reversals in 100 cases");
                    }
                    word("in", "Write: " + n + " ... in 50 cases");
                    int in = whole("the number of cases to look at");
                    cases();
                    rule =
                            new io.github.llm4j.loom.ast.DecisionDef.DropRule(
                                    to, count, n, in, 0, first.getLine());
                }
                d.getDropRules().add(rule);
            } else if (wordIs("moving")) {
                advance();
                word(
                        "up",
                        "Write: moving up needs approval from: someone, or: moving up is automatic");
                if (wordIs("needs")) {
                    advance();
                    word("approval", "Write: moving up needs approval from: risk-owner");
                    word("from", "Write: moving up needs approval from: risk-owner");
                    consume(TokenType.COLON, "Expect ':' after \"approval from\".");
                    d.setApprover(
                            consume(TokenType.IDENTIFIER, "Expect the name of who approves.")
                                    .getValue());
                } else if (wordIs("is")) {
                    advance();
                    word("automatic", "Write: moving up is automatic");
                    d.setAutomatic(true);
                } else {
                    throw error(
                            peek(),
                            "Write: moving up needs approval from: risk-owner, or: moving up is automatic");
                }
            } else {
                throw error(
                        first,
                        "Unexpected \""
                                + first.getValue()
                                + "\" in trust. Write one of: start at, never go above, to <level>:, judge on the latest, check N% of cases, "
                                + "always ask a person, drop to <level> when, moving up.");
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after the trust block.");
    }

    /**
     * True when the token can open a duration: {@code 90s} (a number) or {@code "90s"} (a string).
     */
    private static boolean isDurationStart(Token t) {
        return t.getType() == TokenType.NUMBER_LITERAL || t.getType() == TokenType.STRING_LITERAL;
    }

    /**
     * {@code sleep 30s}, {@code sleep 5m}, {@code sleep 2h}, {@code sleep 1d}; a contextual word,
     * so {@code sleep} stays usable as a variable name.
     */
    private SleepStmt parseSleep() {
        Token word = advance();
        int from = current;
        java.time.Duration duration = duration(word);
        Token first = tokens.get(from);
        StringBuilder text = new StringBuilder();
        for (int i = from; i < current; i++) text.append(tokens.get(i).getValue());
        String written =
                first.getType() == TokenType.STRING_LITERAL
                        ? first.getValue().trim()
                        : text.toString();
        return new SleepStmt(duration, written);
    }

    // -----------------------------------------------------------------------
    // Conditions, and the statements that end a run or give a variable a value.
    // -----------------------------------------------------------------------

    /**
     * The text of a condition, up to the closing bracket: its tokens joined, as ever, with one
     * addition. {@code name is ok} and {@code name is not ok} are kept as those words (with single
     * spaces), which is how {@code ConditionEvaluator} recognises them.
     */
    private String readCondition() {
        StringBuilder text = new StringBuilder();
        while (!check(TokenType.RPAREN) && !isAtEnd()) {
            if (isWord("and") || isWord("or")) {
                throw error(
                        peek(),
                        "a condition is one comparison, such as (score < 7), or one true/false variable; combine several tests into a variable first");
            }
            if (text.length() > 0 && isWord("is")) {
                if ("ok".equals(peekAt(1).getValue())) {
                    advance();
                    advance();
                    text.append(" is ok");
                    continue;
                }
                if ("not".equals(peekAt(1).getValue()) && "ok".equals(peekAt(2).getValue())) {
                    advance();
                    advance();
                    advance();
                    text.append(" is not ok");
                    continue;
                }
            }
            text.append(advance().getValue());
        }
        return text.toString();
    }


    // -----------------------------------------------------------------------
    // Phase 33: start, wait, cancel, steer, and supervise on a handle. Contextual words, like
    // `run`: a verb only where a statement starts and the shape it needs follows.
    // -----------------------------------------------------------------------

    /** A placeholder such as {name}: refused in a steer, where data must not choose the words. */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{[^{}]*\\}");

    /** {@code max_children: N} and {@code max_depth: N}. */
    private void parseChildLimit(LoomScript script) {
        Token keyword = advance();
        advance(); // the colon
        Token n = consume(TokenType.NUMBER_LITERAL, "Write " + keyword.getValue() + ": 3.");
        int value = (int) Double.parseDouble(n.getValue());
        if (value < 1) throw error(n, keyword.getValue() + " must be at least 1.");
        boolean children = "max_children".equals(keyword.getValue());
        if ((children ? script.getMaxChildren() : script.getMaxDepth()) != null)
            throw error(keyword, "Only one " + keyword.getValue() + " can be given.");
        if (children) script.setMaxChildren(value);
        else script.setMaxDepth(value);
    }

    /** {@code start Name(}: the verb is a word only with a workflow name and '(' after it. */
    private boolean isStartStatement() {
        return isWord("start") && isNameToken(peekAt(1)) && peekAt(2).getType() == TokenType.LPAREN;
    }

    /**
     * {@code notify handle "name"} or {@code notify parent "name"}: the verb is a word only with a
     * handle (or {@code parent}) and then a quoted name (D17); anywhere else it is an ordinary name.
     */
    private boolean isNotifyStatement() {
        if (!isWord("notify") || !isNameToken(peekAt(1))) return false;
        return peekAt(2).getType() == TokenType.STRING_LITERAL || peekAt(2).getType() == TokenType.LBRACKET;
    }

    /** The offset of the token after a handle that starts at offset 1 ({@code work} or {@code work[key]}). */
    private int afterHandle() {
        return peekAt(2).getType() == TokenType.LBRACKET ? 5 : 2;
    }

    /**
     * {@code wait signal "name"} (a child) or {@code wait handle signal "name"} (a parent): {@code
     * signal} is a word only here, with a quoted name after it; a handle called {@code signal} is
     * still waited for as any handle (D17).
     */
    private boolean isSignalWaitStatement() {
        if (!isWord("wait")) return false;
        if ("signal".equals(peekAt(1).getValue()) && peekAt(2).getType() == TokenType.STRING_LITERAL) return true;
        if (!isNameToken(peekAt(1))) return false;
        int k = afterHandle();
        return "signal".equals(peekAt(k).getValue()) && peekAt(k + 1).getType() == TokenType.STRING_LITERAL;
    }

    private io.github.llm4j.loom.ast.NotifyStmt parseNotify() {
        advance(); // notify
        io.github.llm4j.loom.ast.HandleRef target = null;
        if (isWord("parent") && peekAt(1).getType() == TokenType.STRING_LITERAL) advance();
        else target = parseHandleRef("notify");
        Token name = consume(TokenType.STRING_LITERAL, "Name the signal in quotes: notify work \"go\"");
        String text = null;
        if (check(TokenType.STRING_LITERAL)) text = advance().getValue();
        boolean sticky = false;
        if (isWord("sticky")) {
            advance();
            sticky = true;
        }
        return new io.github.llm4j.loom.ast.NotifyStmt(target, name.getValue(), text, sticky);
    }

    private io.github.llm4j.loom.ast.SignalWaitStmt parseSignalWait() {
        advance(); // wait
        io.github.llm4j.loom.ast.HandleRef target = null;
        if (!"signal".equals(peek().getValue()) || peekAt(1).getType() != TokenType.STRING_LITERAL)
            target = parseHandleRef("wait");
        advance(); // signal
        Token name = consume(TokenType.STRING_LITERAL, "Name the signal in quotes: wait signal \"go\" timeout 10m -> text");
        var stmt = new io.github.llm4j.loom.ast.SignalWaitStmt(target, name.getValue());
        while (true) {
            if (isWord("timeout")) {
                Token option = advance();
                if (stmt.getTimeoutMillis() != null) throw error(option, "A wait can have only one timeout.");
                stmt.setTimeoutMillis(durationMillis());
            } else if (check(TokenType.ARROW)) {
                advance();
                stmt.setResultVariable(consume(TokenType.IDENTIFIER, "Expect a variable name after '->'.").getValue());
            } else {
                return stmt;
            }
        }
    }

    /** {@code wait handle} or {@code wait all handles}: a name after the verb. */
    private boolean isWaitStatement() {
        return isWord("wait") && isNameToken(peekAt(1));
    }

    /** {@code cancel handle}: a name after the verb. */
    private boolean isCancelStatement() {
        return isWord("cancel") && isNameToken(peekAt(1));
    }

    /** {@code steer handle Agent "text"}, or with a keyed handle. */
    private boolean isSteerStatement() {
        if (!isWord("steer") || !isNameToken(peekAt(1))) return false;
        int i = 2;
        if (peekAt(i).getType() == TokenType.LBRACKET) {
            while (peekAt(i).getType() != TokenType.RBRACKET && i < tokens.size()) i++;
            i++;
        }
        return isNameToken(peekAt(i)) && peekAt(i + 1).getType() == TokenType.STRING_LITERAL;
    }

    /** {@code supervise handle {} or {@code supervise handle budget ...} in a workflow body. */
    private boolean isSuperviseStatement() {
        if (!isWord("supervise") || !isNameToken(peekAt(1))) return false;
        Token after = peekAt(2);
        return after.getType() == TokenType.LBRACE
                || after.getType() == TokenType.LBRACKET
                || "budget".equals(after.getValue());
    }

    /** {@code name} or {@code name[key]} or {@code name["key"]}. */
    private HandleRef parseHandleRef(String what) {
        Token name = advance();
        if (!isNameToken(name))
            throw error(name, "Expect the handle " + what + " names, a variable bound by start.");
        if (match(TokenType.LBRACKET)) {
            Token key = advance();
            boolean literal = key.getType() == TokenType.STRING_LITERAL;
            if (!literal && !(key.getValue() != null && key.getValue().matches("[A-Za-z][A-Za-z0-9_.-]*")))
                throw error(key, "Expect a key in " + name.getValue() + "[...]: a name or a quoted word.");
            consume(TokenType.RBRACKET, "Expect ']' after the key.");
            return new HandleRef(name.getValue(), key.getValue(), literal);
        }
        return HandleRef.variable(name.getValue());
    }

    /** {@code start Workflow(args) [timeout 20m] [budget { }] -> handle}, after the cursor is at start. */
    private StartStmt parseStart() {
        advance();
        Token workflow = advance();
        consume(TokenType.LPAREN, "Expect '(' after the workflow name.");
        List<RunStmt.Arg> args = parseCallArgs("start", workflow);
        Long timeout = null;
        io.github.llm4j.loom.ast.BudgetDef budget = null;
        while (isWord("timeout") || isWord("budget")) {
            Token option = advance();
            if ("timeout".equals(option.getValue())) {
                if (timeout != null) throw error(option, "A start can have only one timeout.");
                timeout = durationMillis();
            } else {
                if (budget != null) throw error(option, "A start can have only one budget.");
                budget = parseBudgetBlock(false);
            }
        }
        consume(
                TokenType.ARROW,
                "Expect '-> handle' after start " + workflow.getValue()
                        + "(...), as in: start " + workflow.getValue() + "(...) budget { tokens: 50000 } -> work");
        StartStmt stmt = new StartStmt(workflow.getValue(), args, parseHandleRef("start"));
        stmt.setTimeoutMillis(timeout);
        stmt.setBudget(budget);
        return stmt;
    }

    /** {@code wait [all] handle [timeout 5m] [-> result] [on_timeout { }] [on_failure { }]}. */
    private WaitStmt parseWait() {
        advance();
        boolean all = false;
        if (isWord("all") && isNameToken(peekAt(1))) {
            advance();
            all = true;
        }
        WaitStmt stmt = new WaitStmt(parseHandleRef("wait"), all);
        while (true) {
            if (isWord("timeout")) {
                Token option = advance();
                if (stmt.getTimeoutMillis() != null)
                    throw error(option, "A wait can have only one timeout.");
                stmt.setTimeoutMillis(durationMillis());
            } else if (check(TokenType.ARROW)) {
                advance();
                stmt.setResultVariable(
                        consume(TokenType.IDENTIFIER, "Expect a variable name after '->'.")
                                .getValue());
            } else if (isWord("on_timeout")) {
                advance();
                parseBlockInto(stmt.getOnTimeout(), "on_timeout");
            } else if (match(TokenType.ON_FAILURE)) {
                parseBlockInto(stmt.getOnFailure(), "on_failure");
            } else {
                return stmt;
            }
        }
    }

    private void parseBlockInto(List<Statement> into, String name) {
        consume(TokenType.LBRACE, "Expect '{' before the " + name + " body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) into.add(parseStatement());
        consume(TokenType.RBRACE, "Expect '}' after the " + name + " body.");
    }

    /** {@code cancel handle [outcome | rewind to cp at most N times] [reason "..."]}. */
    private CancelStmt parseCancel() {
        advance();
        CancelStmt stmt = new CancelStmt(parseHandleRef("cancel"));
        if (isWord("rewind") && peekAt(1).getType() == TokenType.TO) {
            advance();
            advance();
            Token checkpoint =
                    consume(TokenType.IDENTIFIER, "Expect the name of a checkpoint after 'rewind to'.");
            if (!isWord("at"))
                throw error(
                        peek(), "A rewind needs a limit: add \"at most 2 times\" (or another number).");
            advance();
            expectWord("most", "Write the limit as: at most 2 times");
            Token n = consume(TokenType.NUMBER_LITERAL, "Expect a number after 'at most'.");
            int atMost = (int) Double.parseDouble(n.getValue());
            if (atMost < 1) throw error(n, "\"at most\" must be at least 1 time");
            if (isWord("times") || isWord("time")) advance();
            stmt.setRewind(checkpoint.getValue(), atMost);
        } else {
            OutcomeStmt outcome = parseOutcome(1);
            if (outcome != null) stmt.setOutcome(outcome.getKind());
        }
        if (isWord("reason")) {
            advance();
            stmt.setReason(
                    consume(TokenType.STRING_LITERAL, "Write the reason in quotes: reason \"not needed\"")
                            .getValue());
        }
        return stmt;
    }

    /** {@code steer handle Agent "text"}: the text is literal. */
    private SteerStmt parseSteer() {
        advance();
        HandleRef target = parseHandleRef("steer");
        Token agent = advance();
        Token text = consume(TokenType.STRING_LITERAL, "Write what to tell the agent in quotes.");
        requireLiteral(text, "steer");
        return new SteerStmt(target, agent.getValue(), text.getValue());
    }

    private void requireLiteral(Token text, String what) {
        if (PLACEHOLDER.matcher(text.getValue()).find())
            throw error(
                    text,
                    "The text of a "
                            + what
                            + " is literal, written in the script: a placeholder such as {name} would let data decide what an agent is told. Write the words out.");
    }

    /** {@code supervise handle [budget { }] { when ... assert ... }}. */
    private SuperviseStmt parseSuperviseStatement() {
        Token keyword = advance();
        SuperviseStmt stmt = new SuperviseStmt(parseHandleRef("supervise"));
        if (isWord("budget") && peekAt(1).getType() == TokenType.LBRACE) {
            advance();
            stmt.setBudget(parseBudgetBlock(false));
        }
        consume(TokenType.LBRACE, "Expect '{' after supervise " + stmt.getTarget().describe() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token head = advance();
            if ("when".equals(head.getValue()) && "signal".equals(peek().getValue()) && peekAt(1).getType() == TokenType.STRING_LITERAL)
                parseSignalRule(stmt, head);
            else if ("when".equals(head.getValue())) parseCountedRule(stmt, head);
            else if ("assert".equals(head.getValue())) parseAssertion(stmt, head);
            else
                throw error(
                        head,
                        "Inside supervise, write a rule: when tokens > 90000 { cancel }, or an assertion: assert never calls shell { cancel }");
        }
        consume(TokenType.RBRACE, "Expect '}' after supervise.");
        if (stmt.getCounted().isEmpty() && stmt.getCode().isEmpty() && stmt.getJudged().isEmpty() && stmt.getSignals().isEmpty())
            throw error(keyword, "supervise " + stmt.getTarget().describe() + " has no rules.");
        return stmt;
    }

    /** {@code when signal "name" { actions }}: fires on an arrival, never on what the signal says. */
    private void parseSignalRule(SuperviseStmt stmt, Token when) {
        advance(); // signal
        Token name = advance();
        ActionBlock block = parseActionBlock("when signal \"" + name.getValue() + "\"", when, false);
        stmt.getSignals().add(new SuperviseStmt.SignalRule(name.getValue(), block.once(), block.actions(), when.getLine()));
    }

    private static final String COUNTERS = "tokens, cost, calls, elapsed or idle";

    private void parseCountedRule(SuperviseStmt stmt, Token when) {
        Token word = advance();
        SuperviseStmt.Counter counter = SuperviseStmt.Counter.ofWord(word.getValue());
        if (counter == null)
            throw error(
                    word,
                    "\"" + word.getValue() + "\" is not something to count. A rule watches " + COUNTERS
                            + "; a child's status is read with its handle, not watched.");
        SuperviseStmt.Comparison comparison = comparison(word);
        Long number = null;
        java.math.BigDecimal money = null;
        if (counter == SuperviseStmt.Counter.COST) {
            Token amount =
                    consume(TokenType.STRING_LITERAL, "Write the money in quotes: when cost > \"$0.50\"");
            money = new java.math.BigDecimal(amount.getValue().replaceAll("[^0-9.]", ""));
        } else if (counter.isDuration()) {
            number = durationMillis();
        } else {
            Token n = consume(TokenType.NUMBER_LITERAL, "Write a number after " + counter.word() + ".");
            number = (long) Double.parseDouble(n.getValue());
        }
        ActionBlock block = parseActionBlock("when " + counter.word(), when, false);
        stmt.getCounted()
                .add(
                        new SuperviseStmt.Counted(
                                counter, comparison, number, money, block.once(), block.actions(),
                                when.getLine()));
    }

    private SuperviseStmt.Comparison comparison(Token after) {
        Token op = advance();
        return switch (op.getType()) {
            case GT -> SuperviseStmt.Comparison.GREATER;
            case GTE -> SuperviseStmt.Comparison.GREATER_OR_EQUAL;
            case LT -> SuperviseStmt.Comparison.LESS;
            case LTE -> SuperviseStmt.Comparison.LESS_OR_EQUAL;
            case EQUALS -> SuperviseStmt.Comparison.EQUAL;
            default -> throw error(
                    op, "Compare " + after.getValue() + " with >, >=, <, <= or ==.");
        };
    }

    private void parseAssertion(SuperviseStmt stmt, Token assertion) {
        int line = assertion.getLine();
        if (check(TokenType.STRING_LITERAL)) {
            parseJudged(stmt, assertion);
            return;
        }
        if (isWord("never") && "calls".equals(peekAt(1).getValue())) {
            advance();
            advance();
            Token name = advance();
            ActionBlock block = parseActionBlock("assert never calls", assertion, false);
            stmt.getCode()
                    .add(
                            new SuperviseStmt.CodeAssertion(
                                    SuperviseStmt.AssertionKind.NEVER_CALLS, name.getValue(), null,
                                    null, null, block.once(), block.actions(), line));
        } else if (isWord("calls")) {
            advance();
            Token first = advance();
            if (isWord("at")) {
                advance();
                expectWord("most", "Write the limit as: calls search at most 5 times");
                Token n = consume(TokenType.NUMBER_LITERAL, "Expect a number after 'at most'.");
                if (isWord("times") || isWord("time")) advance();
                ActionBlock block = parseActionBlock("assert calls", assertion, false);
                stmt.getCode()
                        .add(
                                new SuperviseStmt.CodeAssertion(
                                        SuperviseStmt.AssertionKind.CALLS_AT_MOST, first.getValue(),
                                        null, (int) Double.parseDouble(n.getValue()), null,
                                        block.once(), block.actions(), line));
            } else {
                expectWord("before", "Write: assert calls plan before write { ... }");
                Token second = advance();
                ActionBlock block = parseActionBlock("assert calls", assertion, false);
                stmt.getCode()
                        .add(
                                new SuperviseStmt.CodeAssertion(
                                        SuperviseStmt.AssertionKind.CALLS_BEFORE, first.getValue(),
                                        second.getValue(), null, null, block.once(), block.actions(),
                                        line));
            }
        } else if (isWord("reaches")) {
            advance();
            Token checkpoint =
                    consume(TokenType.STRING_LITERAL, "Write the checkpoint in quotes: reaches \"collected\"");
            expectWord("within", "Write: assert reaches \"collected\" within 10m { ... }");
            long within = durationMillis();
            ActionBlock block = parseActionBlock("assert reaches", assertion, false);
            stmt.getCode()
                    .add(
                            new SuperviseStmt.CodeAssertion(
                                    SuperviseStmt.AssertionKind.REACHES_WITHIN, checkpoint.getValue(),
                                    null, null, within, block.once(), block.actions(), line));
        } else if (peek().getValue() != null
                && peek().getValue().startsWith("progress.")
                && "changes".equals(peekAt(1).getValue())) {
            String field = advance().getValue().substring("progress.".length());
            advance(); // changes
            expectWord("within", "Write: assert progress.done changes within 5m { ... }");
            long within = durationMillis();
            ActionBlock block = parseActionBlock("assert progress", assertion, false);
            stmt.getCode()
                    .add(
                            new SuperviseStmt.CodeAssertion(
                                    SuperviseStmt.AssertionKind.CHANGES_WITHIN, field, null, null,
                                    within, block.once(), block.actions(), line));
        } else {
            throw error(
                    peek(),
                    "An assertion is: never calls <name>, calls <name> at most N times, calls A before B, reaches \"checkpoint\" within 10m, progress.<field> changes within 5m, or a quoted sentence judged using an evaluator.");
        }
    }

    private void parseJudged(SuperviseStmt stmt, Token assertion) {
        Token text = advance();
        expectWord("using", "Name the judge: assert \"stays on topic\" using TopicJudge every 2m max 4 { ... }");
        Token evaluator = advance();
        boolean seeing = false;
        if (isWord("seeing")) {
            advance();
            expectWord("content", "Write: seeing content");
            seeing = true;
        }
        expectWord("every", "Say how often: every 2m or every 3 steps");
        Long everyMillis = null;
        Integer everySteps = null;
        Token n = peek();
        if (peekAt(1).getType() == TokenType.IDENTIFIER && "steps".equals(peekAt(1).getValue())) {
            advance();
            advance();
            everySteps = (int) Double.parseDouble(n.getValue());
            if (everySteps < 1) throw error(n, "every needs at least 1 step.");
        } else {
            everyMillis = durationMillis();
        }
        if (!isWord("max"))
            throw error(
                    peek(),
                    "A judged assertion needs a ceiling on how often the judge is asked: add max 4 after the cadence.");
        advance();
        Token max = consume(TokenType.NUMBER_LITERAL, "Write a number after max.");
        int ceiling = (int) Double.parseDouble(max.getValue());
        if (ceiling < 1) throw error(max, "max must be at least 1.");
        ActionBlock block = parseActionBlock("assert", assertion, true);
        stmt.getJudged()
                .add(
                        new SuperviseStmt.Judged(
                                text.getValue(), evaluator.getValue(), seeing, everyMillis,
                                everySteps, ceiling, block.once(), block.actions(),
                                assertion.getLine()));
    }

    private record ActionBlock(List<SuperviseDef.Action> actions, boolean once) {}

    /**
     * {@code { steer "text" | wrap_up | cancel [outcome] | once ... }}: the actions of a rule.
     * A judged assertion may only steer, wrap up or cancel use partial (D25).
     */
    private ActionBlock parseActionBlock(String rule, Token head, boolean judged) {
        consume(TokenType.LBRACE, "Expect '{' after " + rule + ".");
        boolean once = false;
        List<SuperviseDef.Action> actions = new java.util.ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token verb = advance();
            String word = verb.getValue();
            if ("steer".equals(word)) {
                Token text =
                        consume(
                                TokenType.STRING_LITERAL,
                                "Write what to tell the agent: steer \"Use a different source.\"");
                requireLiteral(text, "steer");
                actions.add(new SuperviseDef.Action(SuperviseDef.Do.STEER, text.getValue(), null));
            } else if ("wrap_up".equals(word)) {
                actions.add(new SuperviseDef.Action(SuperviseDef.Do.WRAP_UP, null, null));
            } else if ("cancel".equals(word)) {
                OutcomeStmt outcome = parseOutcome(1);
                OutcomeStmt.Kind kind = outcome == null ? null : outcome.getKind();
                if (judged && kind != OutcomeStmt.Kind.USE_PARTIAL)
                    throw error(
                            verb,
                            "A judged assertion may only steer, wrap_up or cancel use partial: a judge's opinion is not enough to discard work or send a child back. Use a counted rule or an assertion in code for that.");
                actions.add(new SuperviseDef.Action(SuperviseDef.Do.CANCEL, null, kind));
            } else if ("once".equals(word)) {
                once = true;
            } else {
                throw error(
                        verb,
                        "A rule can steer \"...\", wrap_up or cancel [outcome], and end with once; not \""
                                + word
                                + "\".");
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after the rule.");
        if (actions.isEmpty())
            throw error(head, "The rule " + rule + " does nothing; give it steer, wrap_up or cancel.");
        return new ActionBlock(actions, once);
    }

    /** {@code halt "reason"} */
    private HaltStmt parseHalt() {
        advance();
        Token reason =
                consume(
                        TokenType.STRING_LITERAL,
                        "Say why in quotes after halt, as in: halt \"the build could not be fixed: {check_run.reason}\"");
        return new HaltStmt(reason.getValue());
    }

    /** {@code set name = value} */
    private SetStmt parseSet() {
        advance();
        Token name = advance();
        if (!isNameToken(name) || !check(TokenType.ASSIGN))
            throw error(name, "Write set as: set name = value");
        if (name.getValue().startsWith("_"))
            throw error(
                    name,
                    "A variable whose name starts with _ is the runtime's: choose another name than "
                            + name.getValue());
        advance(); // =
        return new SetStmt(name.getValue(), parseValue());
    }

    /** {@code return value} */
    private ReturnStmt parseReturn() {
        advance();
        return new ReturnStmt(parseValue());
    }

    /**
     * A text, a number, true or false, a variable or {@code var.path}, or an object {@code { key:
     * value, ... }}.
     */
    private io.github.llm4j.loom.ast.ValueExpr parseValue() {
        Token t = peek();
        if (match(TokenType.STRING_LITERAL))
            return new io.github.llm4j.loom.ast.ValueExpr.Text(previous().getValue());
        if (match(TokenType.NUMBER_LITERAL))
            return new io.github.llm4j.loom.ast.ValueExpr.Num(previous().getValue());
        if (match(TokenType.LBRACE)) {
            java.util.Map<String, io.github.llm4j.loom.ast.ValueExpr> fields =
                    new java.util.LinkedHashMap<>();
            if (!check(TokenType.RBRACE)) {
                do {
                    Token key = advance();
                    if (!isNameToken(key) && key.getType() != TokenType.STRING_LITERAL)
                        throw error(key, "Expect a field name, as in { verdict: \"PASSED\" }.");
                    if (fields.containsKey(key.getValue()))
                        throw error(key, "The field " + key.getValue() + " is given twice.");
                    consume(
                            TokenType.COLON,
                            "Expect ':' after "
                                    + key.getValue()
                                    + ", as in "
                                    + key.getValue()
                                    + ": value.");
                    fields.put(key.getValue(), parseValue());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACE, "Expect '}' to close the object.");
            return new io.github.llm4j.loom.ast.ValueExpr.Obj(fields);
        }
        if (isNameToken(t)
                || (t.getValue() != null && t.getValue().matches("[A-Za-z][A-Za-z0-9_.-]*"))) {
            advance();
            if ("true".equals(t.getValue()))
                return new io.github.llm4j.loom.ast.ValueExpr.Bool(true);
            if ("false".equals(t.getValue()))
                return new io.github.llm4j.loom.ast.ValueExpr.Bool(false);
            return new io.github.llm4j.loom.ast.ValueExpr.Ref(t.getValue());
        }
        throw error(
                t,
                "Expect a value: a quoted text, a number, true or false, a variable (request.amount), or an object { key: value }.");
    }

    private NoteStmt parseNoteStmt() {
        Token msg = consume(TokenType.STRING_LITERAL, "Expect string literal after note.");
        return new NoteStmt(msg.getValue());
    }

    private HandoffStmt parseHandoffStmt() {
        // Form: handoff <payload> to <agent>
        // Payload might be a string literal or an identifier for now
        String payload;
        if (match(TokenType.STRING_LITERAL)) {
            payload = previous().getValue();
        } else if (match(TokenType.IDENTIFIER)) {
            payload = previous().getValue();
        } else {
            throw error(peek(), "Expect string literal or variable payload for handoff.");
        }

        consume(TokenType.TO, "Expect 'to' after handoff payload.");
        Token target = consume(TokenType.IDENTIFIER, "Expect target agent identifier.");

        HandoffStmt handoff = new HandoffStmt(payload, target.getValue());
        parseAttachments(handoff.getAttachments());
        return handoff;
    }

    /**
     * The optional {@code attach image "a.png", audio "b.wav"} clause, which follows the target of
     * a step that sends a task to an agent. {@code attach} is a word only here, so an agent or a
     * variable of that name still works everywhere else.
     */
    private void parseAttachments(java.util.List<AttachItem> into) {
        if (!isWord("attach")) return;
        advance();
        do {
            Token kindWord = peek();
            MediaKind kind =
                    check(TokenType.IDENTIFIER) ? mediaKindOf(kindWord.getValue()) : null;
            if (kind == null) {
                throw error(
                        kindWord,
                        "attach takes image, audio, video or document, as in attach image \"photo.png\""
                                + (kindWord.getValue() == null
                                        ? "."
                                        : ", not \"" + kindWord.getValue() + "\"."));
            }
            advance();
            Token file =
                    consume(
                            TokenType.STRING_LITERAL,
                            "Write the file after " + kind.label() + ": attach " + kind.label() + " \"photo.png\"");
            into.add(new AttachItem(kind, file.getValue(), kindWord.getLine()));
        } while (match(TokenType.COMMA));
    }

    private static MediaKind mediaKindOf(String word) {
        return switch (word) {
            case "image" -> MediaKind.IMAGE;
            case "audio" -> MediaKind.AUDIO;
            case "video" -> MediaKind.VIDEO;
            case "document" -> MediaKind.DOCUMENT;
            default -> null;
        };
    }

    private DelegateStmt parseDelegateStmt() {
        // Form: delegate <payload> to <agent> -> <var>
        String payload;
        if (match(TokenType.STRING_LITERAL)) {
            payload = previous().getValue();
        } else if (match(TokenType.IDENTIFIER)) {
            payload = previous().getValue();
        } else {
            throw error(peek(), "Expect string literal or variable payload for delegate.");
        }

        consume(TokenType.TO, "Expect 'to' after delegate payload.");
        String target = nameOrReference("Expect target agent identifier (or {item.field}).");
        java.util.List<String> allowed = new java.util.ArrayList<>();
        if (isWord("in") && target.startsWith("{")) {
            advance();
            consume(TokenType.LBRACKET, "Write the agents the model may choose in brackets: in [Researcher, Writer].");
            do {
                allowed.add(consume(TokenType.IDENTIFIER, "Expect an agent name in the list.").getValue());
            } while (match(TokenType.COMMA));
            consume(TokenType.RBRACKET, "Expect ']' after the agents.");
        } else if (isWord("in")) {
            throw error(peek(), "in [..] is for a target chosen at run time ({task.owner}); " + target + " is already one agent.");
        }

        java.util.List<AttachItem> attachments = new java.util.ArrayList<>();
        parseAttachments(attachments);

        consume(TokenType.ARROW, "Expect '->' to assign delegate result.");
        String varName = nameOrReference("Expect variable name for result (or {item.field}).");

        DelegateStmt stmt = new DelegateStmt(payload, target, varName);
        stmt.getAllowedTargets().addAll(allowed);
        stmt.getAttachments().addAll(attachments);

        // Optional per-step schema: the same agent can return different structures in different
        // steps.
        if (check(TokenType.IDENTIFIER) && "expecting".equals(peek().getValue())) {
            advance();
            stmt.setExpecting(parseSchema());
        }

        // Optional, in any order: retry 3 · backoff 2s · timeout 90s · budget 5000 tokens
        while (true) {
            if (match(TokenType.RETRY)) {
                Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
                stmt.setRetryCount((int) Double.parseDouble(count.getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue())
                            || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis);
                else stmt.setTimeoutMillis(millis);
            } else if (isBudgetModifier()) {
                stmt.setBudget(parseBudgetModifier(stmt.getBudget()));
            } else {
                break;
            }
        }

        // on_failure and on_stopped, in either order
        while (true) {
            if (match(TokenType.ON_FAILURE)) {
                consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
                while (!check(TokenType.RBRACE) && !isAtEnd()) {
                    stmt.getOnFailure().add(parseStatement());
                }
                consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
            } else if (isBlockKeyword("on_stopped")) {
                Token keyword = advance();
                if (stmt.hasOnStopped())
                    throw error(keyword, "A step can have only one on_stopped block.");
                stmt.setHasOnStopped(true);
                consume(TokenType.LBRACE, "Expect '{' before on_stopped body.");
                stopHandlerDepth++;
                try {
                    while (!check(TokenType.RBRACE) && !isAtEnd()) {
                        stmt.getOnStopped().add(parseStatement());
                    }
                } finally {
                    stopHandlerDepth--;
                }
                consume(TokenType.RBRACE, "Expect '}' after on_stopped body.");
            } else {
                break;
            }
        }

        return stmt;
    }

    /**
     * How many {@code on_stopped} blocks the parser is inside; outcome words are statements only
     * there.
     */
    private int stopHandlerDepth;

    /**
     * The outcome statement at the cursor ({@code use partial}, {@code skip}, ...), or null when
     * the cursor is not at one.
     */
    private OutcomeStmt parseOutcome() {
        return parseOutcome(stopHandlerDepth);
    }

    private OutcomeStmt parseOutcome(int depth) {
        if (depth == 0) return null;
        OutcomeStmt.Kind kind = null;
        if (check(TokenType.RETRY)) {
            advance();
            kind = OutcomeStmt.Kind.RETRY;
        } else if (isWord("use")
                && peekAt(1).getType() == TokenType.IDENTIFIER
                && "partial".equals(peekAt(1).getValue())) {
            advance();
            advance();
            kind = OutcomeStmt.Kind.USE_PARTIAL;
        } else if (check(TokenType.IDENTIFIER)
                && OutcomeStmt.Kind.ofPhrase(peek().getValue()) != null) {
            kind = OutcomeStmt.Kind.ofPhrase(advance().getValue());
        }
        return kind == null ? null : new OutcomeStmt(kind);
    }

    /** {@code supervise Agent { when looping(3) { steer "…" once } ... }} after the keyword. */
    private SuperviseDef parseSupervise(Token keyword) {
        Token agent = consume(TokenType.IDENTIFIER, "Expect the agent to supervise.");
        SuperviseDef def = new SuperviseDef(agent.getValue(), keyword.getLine());
        consume(TokenType.LBRACE, "Expect '{' after supervise " + agent.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token when = advance();
            if (!"when".equals(when.getValue()))
                throw error(when, "Inside supervise, write rules as: when looping(3) { ... }");
            SuperviseDef.Rule rule = parseSignal(when.getLine());
            consume(TokenType.LBRACE, "Expect '{' after the condition of the rule.");
            boolean once = false;
            java.util.List<SuperviseDef.Action> actions = new java.util.ArrayList<>();
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                Token verb = advance();
                String word = verb.getValue();
                if ("steer".equals(word)) {
                    Token text =
                            consume(
                                    TokenType.STRING_LITERAL,
                                    "Write what to tell the agent: steer \"Use a different source.\"");
                    actions.add(
                            new SuperviseDef.Action(SuperviseDef.Do.STEER, text.getValue(), null));
                } else if ("wrap_up".equals(word)) {
                    actions.add(new SuperviseDef.Action(SuperviseDef.Do.WRAP_UP, null, null));
                } else if ("cancel".equals(word)) {
                    OutcomeStmt.Kind outcome = null;
                    if (check(TokenType.RETRY)) {
                        advance();
                        outcome = OutcomeStmt.Kind.RETRY;
                    } else if (isWord("use")
                            && peekAt(1).getType() == TokenType.IDENTIFIER
                            && "partial".equals(peekAt(1).getValue())) {
                        advance();
                        advance();
                        outcome = OutcomeStmt.Kind.USE_PARTIAL;
                    } else if (check(TokenType.IDENTIFIER)
                            && OutcomeStmt.Kind.ofPhrase(peek().getValue()) != null)
                        outcome = OutcomeStmt.Kind.ofPhrase(advance().getValue());
                    actions.add(new SuperviseDef.Action(SuperviseDef.Do.CANCEL, null, outcome));
                } else if ("once".equals(word)) {
                    once = true;
                } else if ("retry".equals(word) || verb.getType() == TokenType.RETRY) {
                    throw error(
                            verb,
                            "retry with another agent is not supported in supervise yet; use steer, wrap_up or cancel.");
                } else {
                    throw error(
                            verb,
                            "A rule can steer \"…\", wrap_up or cancel [outcome], and end with once; not \""
                                    + word
                                    + "\".");
                }
            }
            consume(TokenType.RBRACE, "Expect '}' after the rule.");
            if (actions.isEmpty())
                throw error(
                        when,
                        "The rule when "
                                + rule.describe()
                                + " does nothing; give it steer, wrap_up or cancel.");
            SuperviseDef.Rule full =
                    new SuperviseDef.Rule(
                            rule.getSignal(), rule.getTool(), rule.getN(), once, rule.getLine());
            full.getActions().addAll(actions);
            def.getRules().add(full);
        }
        consume(TokenType.RBRACE, "Expect '}' after supervise.");
        if (def.getRules().isEmpty())
            throw error(keyword, "supervise " + agent.getValue() + " has no rules.");
        return def;
    }

    /**
     * The condition after {@code when}: looping(3), tool_failing(Tool, 3), context > 90%, steps >
     * 20.
     */
    private SuperviseDef.Rule parseSignal(int line) {
        Token signal = advance();
        String word = signal.getValue();
        if ("looping".equals(word)) {
            consume(
                    TokenType.LPAREN,
                    "Write looping(3): the same action this many times in a row.");
            int n = whole("looping");
            consume(TokenType.RPAREN, "Expect ')'.");
            return positive(
                    new SuperviseDef.Rule(SuperviseDef.Signal.LOOPING, null, n, false, line),
                    signal);
        }
        if ("tool_failing".equals(word)) {
            consume(TokenType.LPAREN, "Write tool_failing(Search, 3).");
            Token tool = consume(TokenType.IDENTIFIER, "Expect the tool's name.");
            consume(
                    TokenType.COMMA,
                    "Write tool_failing(" + tool.getValue() + ", 3): how many failures in a row.");
            int n = whole("tool_failing");
            consume(TokenType.RPAREN, "Expect ')'.");
            return positive(
                    new SuperviseDef.Rule(
                            SuperviseDef.Signal.TOOL_FAILING, tool.getValue(), n, false, line),
                    signal);
        }
        if ("context".equals(word) || "steps".equals(word)) {
            consume(
                    TokenType.GT,
                    "Write " + word + " > " + ("context".equals(word) ? "90%" : "20") + ".");
            if ("context".equals(word)) {
                int n = (int) percent("the share of the model's window");
                if (n < 1 || n > 100) throw error(signal, "context must be between 1% and 100%.");
                return new SuperviseDef.Rule(SuperviseDef.Signal.CONTEXT, null, n, false, line);
            }
            return positive(
                    new SuperviseDef.Rule(
                            SuperviseDef.Signal.STEPS, null, whole("steps"), false, line),
                    signal);
        }
        throw error(
                signal,
                "\""
                        + word
                        + "\" is not something to watch. The signals are looping(N), tool_failing(Tool, N), context > N% and steps > N.");
    }

    private SuperviseDef.Rule positive(SuperviseDef.Rule rule, Token at) {
        if (rule.getN() < 1) throw error(at, rule.describe() + " needs a number of at least 1.");
        return rule;
    }

    /** {@code intervention { timeout: retry user: ask }} after the keyword. */
    private InterventionDef parseIntervention(Token keyword) {
        InterventionDef def = new InterventionDef();
        def.setLine(keyword.getLine());
        consume(TokenType.LBRACE, "Expect '{' after intervention.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token reason = advance();
            String name = reason.getValue();
            if (!InterventionDef.REASONS.contains(name)) {
                throw error(
                        reason,
                        "\""
                                + name
                                + "\" is not a reason an agent is stopped for. The reasons are: "
                                + String.join(", ", InterventionDef.REASONS)
                                + ".");
            }
            if (def.getByReason().containsKey(name))
                throw error(reason, "The reason " + name + " is given twice in intervention.");
            consume(TokenType.COLON, "Expect ':' after " + name + ".");
            if (check(TokenType.IDENTIFIER) && "wrap_up".equals(peek().getValue())) {
                throw error(
                        peek(),
                        "wrap_up is not an outcome here; name what the run does when the agent is stopped: use partial, skip, discard, retry, suspend, conclude or ask.");
            }
            OutcomeStmt outcome = parseOutcome(1);
            if (outcome == null) {
                throw error(
                        peek(),
                        "Expect an outcome after "
                                + name
                                + ": use partial, skip, discard, retry, suspend, conclude or ask.");
            }
            def.put(name, outcome.getKind(), reason.getLine());
        }
        consume(TokenType.RBRACE, "Expect '}' after intervention.");
        return def;
    }

    /** An identifier, or a {@code {item.field}} reference resolved at run time. */
    private String nameOrReference(String error) {
        if (match(TokenType.LBRACE)) {
            Token ref = consume(TokenType.IDENTIFIER, error);
            consume(TokenType.RBRACE, "Expect '}' after reference.");
            return "{" + ref.getValue() + "}";
        }
        String name = consume(TokenType.IDENTIFIER, error).getValue();
        if (check(TokenType.LBRACKET) && peekAt(1).getType() == TokenType.IDENTIFIER) {
            // the map form: results[task.name] stores under that key in one object variable
            advance();
            Token key = consume(TokenType.IDENTIFIER, "Expect the name of the value that gives the key.");
            consume(TokenType.RBRACKET, "Expect ']' after the key.");
            return name + "[" + key.getValue() + "]";
        }
        return name;
    }

    /** A duration such as {@code 2s}, {@code 500ms}, {@code 3m} (a bare number means seconds). */
    private long durationMillis() {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a duration, e.g. 2s or 500ms");
        double value = Double.parseDouble(n.getValue());
        String unit = "s";
        if (check(TokenType.IDENTIFIER) && List.of("ms", "s", "m", "h").contains(peek().getValue()))
            unit = advance().getValue();
        return (long)
                switch (unit) {
                    case "ms" -> value;
                    case "m" -> value * 60_000;
                    case "h" -> value * 3_600_000;
                    default -> value * 1_000;
                };
    }

    /** for each item in list.path { ... } */
    private ForEachStmt parseForEach(boolean parallel) {
        Token each = consume(TokenType.IDENTIFIER, "Expect 'each' after 'for'.");
        if (!"each".equals(each.getValue()))
            throw error(each, "Expect 'for each <item> in <list>'.");
        Token item = consume(TokenType.IDENTIFIER, "Expect a name for the item.");
        Token in = consume(TokenType.IDENTIFIER, "Expect 'in' after the item name.");
        if (!"in".equals(in.getValue())) throw error(in, "Expect 'for each <item> in <list>'.");
        Token list =
                consume(TokenType.IDENTIFIER, "Expect the list to iterate, e.g. review.fixes.");
        int max = 0;
        if (check(TokenType.IDENTIFIER) && "max".equals(peek().getValue())) {
            Token keyword = advance();
            Token n = consume(TokenType.NUMBER_LITERAL, "Expect the most items, as in max 10.");
            double v = Double.parseDouble(n.getValue());
            if (v < 1 || v != Math.floor(v))
                throw error(
                        keyword,
                        "for each max must be at least 1 and a whole number, got " + n.getValue());
            max = (int) v;
        }
        io.github.llm4j.loom.ast.BudgetDef budget = null;
        while (isBudgetModifier()) budget = parseBudgetModifier(budget);
        consume(TokenType.LBRACE, "Expect '{' before for each body.");
        List<Statement> body = new java.util.ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            body.add(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after for each body.");
        ForEachStmt loop = new ForEachStmt(item.getValue(), list.getValue(), body, parallel);
        loop.setBudget(budget);
        loop.setMax(max);
        if (check(TokenType.IDENTIFIER) && "on_too_many".equals(peek().getValue())) {
            Token keyword = advance();
            if (max == 0)
                throw error(
                        keyword,
                        "on_too_many needs a bound: add max N after the list, as in for each t in plan.tasks max 10 { … } on_too_many { … }");
            consume(TokenType.LBRACE, "Expect '{' before on_too_many body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                loop.getOnTooMany().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_too_many body.");
        }
        if (check(TokenType.IDENTIFIER) && "on_exhausted".equals(peek().getValue())) {
            Token keyword = advance();
            if (budget == null)
                throw error(
                        keyword,
                        "on_exhausted on a for each needs a budget (add 'budget N tokens')");
            consume(TokenType.LBRACE, "Expect '{' before on_exhausted body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                loop.getOnExhausted().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_exhausted body.");
        }
        return loop;
    }

    private CallStmt parseCallStmt() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect workflow name to call.");
        java.util.Map<String, String> args = new java.util.HashMap<>();

        consume(TokenType.LPAREN, "Expect '(' before call arguments.");
        if (!check(TokenType.RPAREN)) {
            do {
                Token key = consume(TokenType.IDENTIFIER, "Expect argument name.");
                consume(TokenType.ASSIGN, "Expect '=' after argument name.");
                String value;
                if (match(TokenType.STRING_LITERAL)) {
                    value = previous().getValue();
                } else if (match(TokenType.IDENTIFIER)) {
                    value = previous().getValue();
                } else {
                    throw error(peek(), "Expect string or variable for argument value.");
                }
                args.put(key.getValue(), value);
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expect ')' after call arguments.");

        consume(TokenType.ARROW, "Expect '->' to assign call result.");
        Token resultVar = consume(TokenType.IDENTIFIER, "Expect variable name for result.");

        return new CallStmt(nameToken.getValue(), args, resultVar.getValue());
    }

    private SchemaDef parseSchema() {
        if (match(TokenType.LBRACE)) {
            SchemaDef schema = new SchemaDef(SchemaDef.Type.OBJECT);
            // Insertion order, so the schema shown to the model reads as written.
            java.util.Map<String, SchemaDef> fields = new java.util.LinkedHashMap<>();
            if (!check(TokenType.RBRACE)) {
                do {
                    // Any word is a valid field name, including ones Loom uses as keywords (note,
                    // model, …).
                    Token fieldName = advance();
                    if (fieldName.getValue() == null
                            || !fieldName.getValue().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw error(fieldName, "Expect field name.");
                    }
                    consume(TokenType.COLON, "Expect ':' after field name.");
                    fields.put(fieldName.getValue(), parseSchema());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACE, "Expect '}' after object schema.");
            schema.setFields(fields);
            return schema;
        } else if (match(TokenType.LIST)) {
            SchemaDef schema = new SchemaDef(SchemaDef.Type.LIST);
            if (match(TokenType.LT)) { // a bare `list` holds anything
                schema.setElementType(parseSchema());
                consume(TokenType.GT, "Expect '>' after list type.");
            }
            return schema;
        } else if (match(TokenType.ENUM)) {
            consume(TokenType.LBRACKET, "Expect '[' after enum.");
            SchemaDef schema = new SchemaDef(SchemaDef.Type.ENUM);
            java.util.List<String> values = new java.util.ArrayList<>();
            do {
                values.add(
                        consume(TokenType.STRING_LITERAL, "Expect string literal in enum.")
                                .getValue());
            } while (match(TokenType.COMMA));
            consume(TokenType.RBRACKET, "Expect ']' after enum values.");
            schema.setEnumValues(values);
            return schema;
        } else if (match(TokenType.IDENTIFIER)) {
            String type = previous().getValue().toLowerCase();
            return switch (type) {
                case "string" -> new SchemaDef(SchemaDef.Type.STRING);
                case "number" -> new SchemaDef(SchemaDef.Type.NUMBER);
                case "boolean" -> new SchemaDef(SchemaDef.Type.BOOLEAN);
                default -> throw error(previous(), "Unknown schema type: " + type);
            };
        }

        throw error(peek(), "Expect schema definition.");
    }

    private AltStmt parseAltStmt() {
        // Form: alt (condition) { } else { }
        consume(TokenType.LPAREN, "Expect '(' before alt condition.");
        String altCondition = readCondition();
        consume(TokenType.RPAREN, "Expect ')' after alt condition.");

        AltStmt altStmt = new AltStmt(altCondition);

        consume(TokenType.LBRACE, "Expect '{' before alt true branch.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            altStmt.addIfStatement(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after alt true branch.");

        if (match(TokenType.ELSE)) {
            consume(TokenType.LBRACE, "Expect '{' before alt else branch.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                altStmt.addElseStatement(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after alt else branch.");
        }

        return altStmt;
    }

    private BroadcastStmt parseBroadcastStmt() {
        String payload;
        if (match(TokenType.STRING_LITERAL)) {
            payload = previous().getValue();
        } else if (match(TokenType.IDENTIFIER)) {
            payload = previous().getValue();
        } else {
            throw error(peek(), "Expect string literal or variable payload for broadcast.");
        }

        consume(TokenType.TO, "Expect 'to' after broadcast payload.");
        consume(TokenType.LBRACKET, "Expect '[' before agent list.");
        List<String> targetAgents = new java.util.ArrayList<>();
        if (!check(TokenType.RBRACKET)) {
            do {
                Token target = consume(TokenType.IDENTIFIER, "Expect target agent identifier.");
                targetAgents.add(target.getValue());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RBRACKET, "Expect ']' after agent list.");

        java.util.List<AttachItem> attachments = new java.util.ArrayList<>();
        parseAttachments(attachments);

        consume(TokenType.ARROW, "Expect '->' to assign broadcast result.");
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name for result.");

        BroadcastStmt broadcast = new BroadcastStmt(payload, targetAgents, varName.getValue());
        broadcast.getAttachments().addAll(attachments);
        while (isBudgetModifier()) broadcast.setBudget(parseBudgetModifier(broadcast.getBudget()));
        return broadcast;
    }

    private LoopStmt parseLoopStmt() {
        consume(TokenType.UNTIL, "Expect 'until' after loop.");
        consume(TokenType.LPAREN, "Expect '(' before loop condition.");
        StringBuilder conditionBuilder = new StringBuilder(readCondition());
        consume(TokenType.RPAREN, "Expect ')' after loop condition.");

        // Optional safety bound: loop until (cond) max 5 { ... } on_exhausted { ... }
        int max = 0;
        if (check(TokenType.IDENTIFIER) && "max".equals(peek().getValue())) {
            advance();
            Token n = consume(TokenType.NUMBER_LITERAL, "Expect a number after 'max', e.g. max 5");
            max = (int) Double.parseDouble(n.getValue());
            if (max < 1) throw error(n, "loop max must be at least 1");
        }
        io.github.llm4j.loom.ast.BudgetDef loopBudget = null;
        while (isBudgetModifier()) loopBudget = parseBudgetModifier(loopBudget);

        consume(TokenType.LBRACE, "Expect '{' before loop body.");
        List<Statement> body = new java.util.ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            body.add(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after loop body.");

        LoopStmt loop = new LoopStmt(conditionBuilder.toString(), body);
        loop.setMaxIterations(max);
        loop.setBudget(loopBudget);
        if (check(TokenType.IDENTIFIER) && "on_exhausted".equals(peek().getValue())) {
            Token keyword = advance();
            if (max == 0 && loopBudget == null) {
                throw error(
                        keyword,
                        "on_exhausted needs a bounded loop (add 'max N' or 'budget N tokens')");
            }
            consume(TokenType.LBRACE, "Expect '{' before on_exhausted body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                loop.getOnExhausted().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_exhausted body.");
        }
        return loop;
    }

    private HumanPromptStmt parseHumanPromptStmt() {
        Token msg =
                consume(
                        TokenType.STRING_LITERAL,
                        "Expect string literal message for human prompt.");
        java.util.List<AttachItem> attachments = new java.util.ArrayList<>();
        parseAttachments(attachments);
        consume(TokenType.ARROW, "Expect '->' to assign human prompt result.");
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name for result.");
        HumanPromptStmt prompt = new HumanPromptStmt(msg.getValue(), varName.getValue());
        prompt.getAttachments().addAll(attachments);
        return prompt;
    }

    // -----------------------------------------------------------------------
    // Tier-1 extension parsers
    // -----------------------------------------------------------------------

    /**
     * Parses a top-level MCP server declaration.
     *
     * <pre>
     * mcp PostgresDB {
     *     transport: "stdio"
     *     cmd: "npx @modelcontextprotocol/server-postgres"
     * }
     * </pre>
     */
    private McpServerDef parseMcpServer() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect MCP server name.");
        McpServerDef mcp = new McpServerDef(nameToken.getValue());
        mcp.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before mcp server body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.TRANSPORT)) {
                consume(TokenType.COLON, "Expect ':' after transport.");
                Token val =
                        consume(TokenType.STRING_LITERAL, "Expect string literal for transport.");
                mcp.setTransport(val.getValue());
            } else if (match(TokenType.CMD)) {
                consume(TokenType.COLON, "Expect ':' after cmd.");
                Token val = consume(TokenType.STRING_LITERAL, "Expect string literal for cmd.");
                mcp.setCmd(val.getValue());
            } else if (check(TokenType.IDENTIFIER) && peek().getValue().equals("effects")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after effects.");
                parseEffectMap(mcp.getName(), mcp::putToolEffect);
            } else {
                throw error(peek(), "Unexpected token in mcp body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after mcp server body.");
        return mcp;
    }

    /**
     * Parses a top-level audit configuration block.
     *
     * <pre>
     * audit {
     *     logger: "file"
     *     path: "./logs/loom-audit.jsonl"
     * }
     * </pre>
     */
    private AuditConfig parseAuditConfig() {
        AuditConfig cfg = new AuditConfig();
        consume(TokenType.LBRACE, "Expect '{' before audit body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.LOGGER)) {
                consume(TokenType.COLON, "Expect ':' after logger.");
                Token val =
                        consume(TokenType.STRING_LITERAL, "Expect string literal for logger type.");
                cfg.setLogger(val.getValue());
            } else if (match(TokenType.PATH)) {
                consume(TokenType.COLON, "Expect ':' after path.");
                Token val =
                        consume(TokenType.STRING_LITERAL, "Expect string literal for audit path.");
                cfg.setPath(val.getValue());
            } else {
                throw error(peek(), "Unexpected token in audit body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after audit body.");
        return cfg;
    }

    private KnowledgeDef parseKnowledgeBase() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect knowledge base name.");
        KnowledgeDef kb = new KnowledgeDef(nameToken.getValue());
        kb.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before knowledge body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.TYPE)) {
                consume(TokenType.COLON, "Expect ':' after type.");
                kb.setType(consume(TokenType.STRING_LITERAL, "Expect type string.").getValue());
            } else if (match(TokenType.PATH)) {
                consume(TokenType.COLON, "Expect ':' after path.");
                kb.setPath(consume(TokenType.STRING_LITERAL, "Expect path string.").getValue());
            } else if (match(TokenType.CHUNK_SIZE)) {
                consume(TokenType.COLON, "Expect ':' after chunk_size.");
                kb.setChunkSize(
                        Integer.parseInt(
                                consume(TokenType.NUMBER_LITERAL, "Expect chunk size number.")
                                        .getValue()));
            } else if (match(TokenType.EMBEDDING)) {
                consume(TokenType.COLON, "Expect ':' after embedding.");
                kb.setEmbeddingProvider(
                        consume(TokenType.STRING_LITERAL, "Expect embedding provider string.")
                                .getValue());
            } else if (check(TokenType.IDENTIFIER) && "source".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after source.");
                kb.setPath(
                        consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a file or directory, e.g. source: \"docs/\"")
                                .getValue());
            } else if (check(TokenType.IDENTIFIER)
                    && ("overlap".equals(peek().getValue()) || "top_k".equals(peek().getValue()))) {
                Token key = advance();
                consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
                Token n =
                        consume(
                                TokenType.NUMBER_LITERAL,
                                "Expect a whole number for " + key.getValue() + ".");
                double v = Double.parseDouble(n.getValue());
                boolean overlap = key.getValue().equals("overlap");
                if (v != Math.floor(v) || v < (overlap ? 0 : 1)) {
                    throw error(
                            n,
                            key.getValue()
                                    + " must be a "
                                    + (overlap ? "non-negative" : "positive")
                                    + " whole number");
                }
                if (overlap) kb.setOverlap((int) v);
                else kb.setTopK((int) v);
            } else if (check(TokenType.IDENTIFIER) && "store".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after store.");
                if (match(TokenType.MEMORY)) kb.setStore(null);
                else
                    kb.setStore(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Expect an index file path, or memory")
                                    .getValue());
            } else if (check(TokenType.IDENTIFIER) && "mode".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after mode.");
                Token m = word("Expect context or tool.");
                switch (m.getValue()) {
                    case "context" -> kb.setMode(
                            io.github.llm4j.loom.ast.KnowledgeDef.Mode.CONTEXT);
                    case "tool" -> kb.setMode(io.github.llm4j.loom.ast.KnowledgeDef.Mode.TOOL);
                    default -> throw error(
                            m, "mode must be context or tool, got '" + m.getValue() + "'");
                }
            } else {
                throw error(peek(), "Unexpected token in knowledge body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after knowledge body.");
        return kb;
    }

    private RoutingPolicyDef parseRoutingPolicy() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect routing policy name.");
        RoutingPolicyDef rp = new RoutingPolicyDef(nameToken.getValue());
        rp.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before routing body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.STRATEGY)) {
                consume(TokenType.COLON, "Expect ':' after strategy.");
                rp.setStrategy(
                        check(TokenType.STRING_LITERAL)
                                ? advance().getValue()
                                : word("Expect a strategy: cost_aware or fallback").getValue());
            } else if (match(TokenType.PRIMARY)) {
                consume(TokenType.COLON, "Expect ':' after primary.");
                rp.setPrimaryModel(
                        consume(TokenType.STRING_LITERAL, "Expect primary model string.")
                                .getValue());
            } else if (match(TokenType.FALLBACK)
                    || (check(TokenType.IDENTIFIER)
                            && "fallbacks".equals(peek().getValue())
                            && advance() != null)) {
                consume(TokenType.COLON, "Expect ':' after fallback.");
                consume(TokenType.LBRACKET, "Expect '['.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        rp.addFallbackModel(
                                consume(TokenType.STRING_LITERAL, "Expect model string.")
                                        .getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']'.");
            } else {
                throw error(peek(), "Unexpected token in routing body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after routing body.");
        return rp;
    }

    /** {@code { key: value … }} into a settings block; keys and values are checked at load time. */
    /** {@code guard { pii: mask  bias: warn  prompt_leak: block  internal_refs: ["POL-\\d+"] }}. */
    private AgentDef.GuardConfig parseGuard(int line) {
        AgentDef.GuardConfig guard = new AgentDef.GuardConfig();
        guard.setLine(line);
        consume(TokenType.LBRACE, "Expect '{' after guard.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a guard setting, e.g. pii: mask");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (guard.has(key.getValue()))
                throw error(key, key.getValue() + " is given twice in guard");
            if (key.getValue().equals("internal_refs")) {
                consume(TokenType.LBRACKET, "Write internal_refs as a list: [\"Section \\\\d+\"].");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        guard.getInternalRefs()
                                .add(
                                        consume(
                                                        TokenType.STRING_LITERAL,
                                                        "Expect a pattern in quotes in internal_refs.")
                                                .getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after internal_refs.");
                guard.put(key.getValue(), ToolDef.OptionValue.literal("list"), key.getLine());
            } else {
                guard.put(key.getValue(), optionValue(), key.getLine());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after guard settings.");
        return guard;
    }

    private <T extends Settings> T parseSettings(T settings, String what, int line) {
        settings.setLine(line);
        consume(TokenType.LBRACE, "Expect '{' after " + what + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a " + what + " setting, e.g. key: value");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (settings.has(key.getValue()))
                throw error(key, key.getValue() + " is given twice in " + what);
            settings.put(key.getValue(), optionValue(), key.getLine());
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after " + what + " settings.");
        return settings;
    }

    /**
     * {@code grounding: web} or {@code grounding: web { allow: ["who.int"] recency: year … }}: like
     * other settings blocks, but {@code allow} and {@code block} take a bracketed list of domains.
     */
    private AgentDef.GroundingConfig parseGrounding(int line) {
        AgentDef.GroundingConfig cfg = new AgentDef.GroundingConfig();
        cfg.setLine(line);
        Token kind = word("Expect the kind of grounding, e.g. grounding: web");
        if (!kind.getValue().equals("web")) {
            throw error(
                    kind,
                    "The only kind of grounding is web (grounding: web), got " + kind.getValue());
        }
        if (!check(TokenType.LBRACE)) return cfg;
        advance();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a grounding setting, e.g. recency: year");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (cfg.has(key.getValue()))
                throw error(key, key.getValue() + " is given twice in grounding");
            if (key.getValue().equals("allow") || key.getValue().equals("block")) {
                java.util.List<String> domains = new java.util.ArrayList<>();
                if (check(TokenType.LBRACKET)) {
                    advance();
                    if (!check(TokenType.RBRACKET)) {
                        do {
                            domains.add(
                                    consume(
                                                    TokenType.STRING_LITERAL,
                                                    "Expect a domain in quotes, e.g. \"who.int\".")
                                            .getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after the domains.");
                } else {
                    domains.add(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Write the domains in brackets, e.g. "
                                                    + key.getValue()
                                                    + ": [\"who.int\", \"nih.gov\"]")
                                    .getValue());
                }
                cfg.put(
                        key.getValue(),
                        io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(
                                String.join(",", domains)),
                        key.getLine());
            } else {
                cfg.put(key.getValue(), optionValue(), key.getLine());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after grounding settings.");
        return cfg;
    }

    /**
     * {@code context { compact: chain(a, b) trigger: 80% … }}: like other settings blocks, but
     * {@code compact} takes names and percentages are written with a sign.
     */
    private AgentDef.ContextConfig parseContextBlock(int line) {
        AgentDef.ContextConfig cfg = new AgentDef.ContextConfig();
        cfg.setLine(line);
        consume(TokenType.LBRACE, "Expect '{' after context.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a context setting, e.g. compact: drop_oldest");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (cfg.has(key.getValue()))
                throw error(key, key.getValue() + " is given twice in context");
            if (key.getValue().equals("compact")) {
                java.util.List<String> names = cfg.compactNames();
                if (check(TokenType.LBRACKET)) {
                    advance();
                    do {
                        names.add(word("Expect a strategy name.").getValue());
                    } while (match(TokenType.COMMA));
                    consume(TokenType.RBRACKET, "Expect ']' after the strategies.");
                } else {
                    Token first = word("Expect a strategy name, e.g. compact: drop_oldest");
                    if (first.getValue().equals("chain") && check(TokenType.LPAREN)) {
                        advance();
                        if (check(TokenType.RPAREN))
                            throw error(peek(), "chain() needs at least one strategy name");
                        do {
                            names.add(word("Expect a strategy name.").getValue());
                        } while (match(TokenType.COMMA));
                        consume(TokenType.RPAREN, "Expect ')' after the strategies.");
                    } else {
                        names.add(first.getValue());
                    }
                }
                cfg.put(
                        key.getValue(),
                        io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(
                                String.join(",", names)),
                        key.getLine());
            } else if (check(TokenType.NUMBER_LITERAL)
                    && tokens.size() > current + 1
                    && tokens.get(current + 1).getType() == TokenType.PERCENT) {
                String number = advance().getValue();
                advance();
                cfg.put(
                        key.getValue(),
                        io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(number + "%"),
                        key.getLine());
            } else if (check(TokenType.NUMBER_LITERAL)) {
                cfg.put(
                        key.getValue(),
                        io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(advance().getValue()),
                        key.getLine());
            } else {
                cfg.put(key.getValue(), optionValue(), key.getLine());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after context settings.");
        return cfg;
    }

    /**
     * {@code voice {} / guard {}}: a contextual keyword followed by a block (with an optional
     * colon).
     */
    private boolean isSettingsBlock(String name) {
        if (!check(TokenType.IDENTIFIER)
                || !name.equals(peek().getValue())
                || tokens.size() <= current + 1) return false;
        TokenType next = tokens.get(current + 1).getType();
        return next == TokenType.LBRACE
                || next == TokenType.COLON
                        && tokens.size() > current + 2
                        && tokens.get(current + 2).getType() == TokenType.LBRACE;
    }

    /** A contextual keyword followed by a name and a block: {@code provider Box { … }}. */
    private boolean isNamedBlock(String name) {
        return check(TokenType.IDENTIFIER)
                && name.equals(peek().getValue())
                && tokens.size() > current + 2
                && tokens.get(current + 1).getType() == TokenType.IDENTIFIER
                && tokens.get(current + 2).getType() == TokenType.LBRACE;
    }

    private ProviderDef parseProviderDef() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect a provider name, e.g. provider Box { use: ollama }");
        ProviderDef provider = new ProviderDef(name.getValue());
        provider.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after provider " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a provider option, e.g. use: ollama");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (key.getValue().equals("use")) {
                provider.setKind(
                        word("Expect a provider kind after use:, e.g. use: ollama").getValue());
            } else {
                if (provider.getOptions().containsKey(key.getValue()))
                    throw error(key, "option " + key.getValue() + " is given twice");
                provider.getOptions().put(key.getValue(), optionValue());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after provider " + name.getValue() + ".");
        if (provider.getKind() == null)
            throw error(
                    name,
                    "provider "
                            + name.getValue()
                            + " needs use: gemini | anthropic | ollama | sarvam | openai | xai | kimi | qwen | perplexity | openai_compatible");
        return provider;
    }

    private static final java.util.List<String> CLASSIFIER_SETTINGS =
            java.util.List.of("model", "samples", "pii", "choose", "ask", "rate", "confidence");

    /**
     * {@code intentclassifier Name { model: "…" samples: 3 choose kind: a | b ask urgent: "…" rate
     * tone: low | high confidence at least 0.8 else escalate }}, after the word {@code
     * intentclassifier}. The words inside are contextual: they mean something only here.
     */
    private io.github.llm4j.loom.ast.IntentClassifierDef parseIntentClassifier() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect a classifier name, e.g. intentclassifier Intent { model: \"…\"  choose kind: a | b }");
        io.github.llm4j.loom.ast.IntentClassifierDef def =
                new io.github.llm4j.loom.ast.IntentClassifierDef(name.getValue());
        def.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after intentclassifier " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key =
                    word(
                            "Expect a classifier setting: "
                                    + String.join(", ", CLASSIFIER_SETTINGS)
                                    + ".");
            switch (key.getValue()) {
                case "model" -> {
                    if (def.getModel() != null)
                        throw error(
                                key,
                                "model is given twice in intentclassifier "
                                        + name.getValue()
                                        + ".");
                    consume(TokenType.COLON, "Expect ':' after model.");
                    def.setModel(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Expect a quoted model, as in model: \"Jev/jev-latest\" or model: \"gemini-2.5-flash\".")
                                    .getValue(),
                            key.getLine());
                }
                case "samples" -> {
                    if (def.getSamplesLine() != 0)
                        throw error(
                                key,
                                "samples is given twice in intentclassifier "
                                        + name.getValue()
                                        + ".");
                    consume(TokenType.COLON, "Expect ':' after samples.");
                    Token n =
                            consume(
                                    TokenType.NUMBER_LITERAL,
                                    "Expect how many independent calls to combine, as in samples: 3.");
                    double v = Double.parseDouble(n.getValue());
                    if (v < 1 || v != Math.floor(v))
                        throw error(
                                n,
                                "samples is a whole number of calls, at least 1; got "
                                        + n.getValue()
                                        + ".");
                    def.setSamples((int) v, key.getLine());
                }
                case "pii" -> {
                    if (def.getPii() != null)
                        throw error(
                                key,
                                "pii is given twice in intentclassifier " + name.getValue() + ".");
                    consume(TokenType.COLON, "Expect ':' after pii.");
                    def.setPii(
                            word("Expect mask, block or warn after pii:.").getValue(),
                            key.getLine());
                }
                case "choose", "rate" -> def.getQuestions()
                        .add(parseClassifierChoice(key, "rate".equals(key.getValue())));
                case "ask" -> def.getQuestions().add(parseClassifierAsk(key));
                case "confidence" -> {
                    if (def.getConfidence() != null)
                        throw error(
                                key,
                                "an intentclassifier has one confidence rule; "
                                        + name.getValue()
                                        + " has two.");
                    def.setConfidence(parseClassifierConfidence(key));
                }
                default -> throw error(key, unknownClassifierSetting(key));
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after intentclassifier " + name.getValue() + ".");
        if (def.getModel() == null)
            throw error(
                    name,
                    "intentclassifier "
                            + name.getValue()
                            + " needs a model, as in model: \"Jev/jev-latest\" or model: \"gemini-2.5-flash\".");
        return def;
    }

    private static String unknownClassifierSetting(Token t) {
        java.util.List<String> near =
                io.github.llm4j.loom.prompt.PromptCatalog.nearest(
                        t.getValue(), CLASSIFIER_SETTINGS);
        return "\""
                + t.getValue()
                + "\" is not something an intentclassifier can have. An intentclassifier can have: "
                + String.join(", ", CLASSIFIER_SETTINGS)
                + "."
                + (near.isEmpty() ? "" : " Did you mean " + String.join(" or ", near) + "?");
    }

    /**
     * {@code choose kind ["instructions"]: a | b | c} or {@code rate tone ["instructions"]: low |
     * high}, after the keyword.
     */
    private io.github.llm4j.loom.ast.IntentClassifierDef.Question parseClassifierChoice(
            Token keyword, boolean scale) {
        Token name =
                word(
                        "Expect a name for the question, as in "
                                + keyword.getValue()
                                + " kind: a | b.");
        String instructions = check(TokenType.STRING_LITERAL) ? advance().getValue() : null;
        consume(
                TokenType.COLON,
                "Expect ':' and the "
                        + (scale ? "points of the scale, lowest first" : "labels")
                        + " of "
                        + name.getValue()
                        + ", as in "
                        + keyword.getValue()
                        + " "
                        + name.getValue()
                        + ": a | b.");
        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(classifierLabel(name));
        while (match(TokenType.PIPE)) labels.add(classifierLabel(name));
        return new io.github.llm4j.loom.ast.IntentClassifierDef.Question(
                scale
                        ? io.github.llm4j.loom.ast.IntentClassifierDef.Kind.RATE
                        : io.github.llm4j.loom.ast.IntentClassifierDef.Kind.CHOOSE,
                name.getValue(),
                instructions,
                java.util.List.copyOf(labels),
                keyword.getLine());
    }

    private String classifierLabel(Token question) {
        if (check(TokenType.STRING_LITERAL)) return advance().getValue();
        return word("Expect a label for "
                        + question.getValue()
                        + " (a word or a quoted text) after ':' or '|'.")
                .getValue();
    }

    /**
     * {@code ask urgent: "Is it urgent?"} (also {@code ask urgent "…"}, or {@code ask urgent} alone
     * for a sentence made from the name).
     */
    private io.github.llm4j.loom.ast.IntentClassifierDef.Question parseClassifierAsk(
            Token keyword) {
        Token name = word("Expect a name for the question, as in ask urgent: \"Is it urgent?\".");
        String instructions = null;
        if (check(TokenType.STRING_LITERAL)) {
            instructions = advance().getValue();
        } else if (match(TokenType.COLON)) {
            instructions =
                    consume(
                                    TokenType.STRING_LITERAL,
                                    "Expect the yes/no question in quotes after ask "
                                            + name.getValue()
                                            + ":.")
                            .getValue();
        }
        return new io.github.llm4j.loom.ast.IntentClassifierDef.Question(
                io.github.llm4j.loom.ast.IntentClassifierDef.Kind.ASK,
                name.getValue(),
                instructions,
                java.util.List.of(),
                keyword.getLine());
    }

    /**
     * {@code confidence at least 0.8 else escalate | ask person | fail}, after {@code confidence};
     * the action defaults to escalate.
     */
    private io.github.llm4j.loom.ast.IntentClassifierDef.Confidence parseClassifierConfidence(
            Token keyword) {
        expectWord("at", "Write the rule as confidence at least 0.8 else escalate.");
        expectWord("least", "Write the rule as confidence at least 0.8 else escalate.");
        Token n =
                consume(
                        TokenType.NUMBER_LITERAL,
                        "Expect a threshold from 0 to 1, as in confidence at least 0.8.");
        double threshold = Double.parseDouble(n.getValue());
        if (threshold < 0 || threshold > 1)
            throw error(n, "the confidence threshold is from 0 to 1; got " + n.getValue() + ".");
        io.github.llm4j.loom.ast.IntentClassifierDef.Action action =
                io.github.llm4j.loom.ast.IntentClassifierDef.Action.ESCALATE;
        if (match(TokenType.ELSE)) {
            if (isWord("escalate")) {
                advance();
            } else if (isWord("ask")) {
                advance();
                expectWord("person", "Write ask person to put the decision to a person.");
                action = io.github.llm4j.loom.ast.IntentClassifierDef.Action.ASK_PERSON;
            } else if (isWord("fail")) {
                advance();
                action = io.github.llm4j.loom.ast.IntentClassifierDef.Action.FAIL;
            } else {
                throw error(
                        peek(),
                        "After else write escalate (the label becomes unsure), ask person, or fail.");
            }
        }
        return new io.github.llm4j.loom.ast.IntentClassifierDef.Confidence(
                threshold, action, keyword.getLine());
    }

    /**
     * {@code intentclassify Intent on message -> intent [retry 2] [backoff 2s] [timeout 30s]
     * [on_failure { … }]}.
     */
    private io.github.llm4j.loom.ast.IntentClassifyStmt parseIntentClassify() {
        Token keyword = advance();
        Token classifier = consume(TokenType.IDENTIFIER, "Expect the name of an intentclassifier.");
        if (!isWord("on"))
            throw error(
                    peek(),
                    "Expect 'on' and what to classify, as in intentclassify "
                            + classifier.getValue()
                            + " on message -> result.");
        advance();
        java.util.List<RunStmt.Arg> inputs = new java.util.ArrayList<>();
        if (match(TokenType.LBRACE)) {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                Token field =
                        word("Expect a field name, as in on { message: message, history: past }.");
                consume(TokenType.COLON, "Expect ':' after " + field.getValue() + ".");
                inputs.add(classifierInput(field.getValue()));
                match(TokenType.COMMA);
            }
            consume(TokenType.RBRACE, "Expect '}' after the fields.");
            if (inputs.isEmpty())
                throw error(
                        previous(),
                        "Name at least one field to classify, as in on { message: message }.");
        } else {
            inputs.add(classifierInput(""));
        }
        consume(
                TokenType.ARROW,
                "Expect '->' and a variable to hold the answers, as in intentclassify "
                        + classifier.getValue()
                        + " on message -> intent.");
        String variable = nameOrReference("Expect a variable name for the answers.");
        io.github.llm4j.loom.ast.IntentClassifyStmt stmt =
                new io.github.llm4j.loom.ast.IntentClassifyStmt(classifier.getValue(), variable);
        stmt.setLine(keyword.getLine());
        stmt.getInputs().addAll(inputs);
        while (true) {
            if (match(TokenType.RETRY)) {
                stmt.setRetryCount(
                        (int)
                                Double.parseDouble(
                                        consume(
                                                        TokenType.NUMBER_LITERAL,
                                                        "Expect number of retries.")
                                                .getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue())
                            || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis);
                else stmt.setTimeoutMillis(millis);
            } else {
                break;
            }
        }
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd())
                stmt.getOnFailure().add(parseStatement());
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    private static final java.util.List<String> EXTRACTOR_SETTINGS =
            java.util.List.of(
                    "model",
                    "instructions",
                    "fields",
                    "optional",
                    "pii",
                    "media",
                    "temperature",
                    "timeout",
                    "budget");

    /** {@code policy Name: "file"}: the word, a name, a colon and a quoted file id. */
    private boolean isPolicyDeclaration() {
        return check(TokenType.IDENTIFIER)
                && "policy".equals(peek().getValue())
                && tokens.size() > current + 3
                && tokens.get(current + 1).getType() == TokenType.IDENTIFIER
                && tokens.get(current + 2).getType() == TokenType.COLON
                && tokens.get(current + 3).getType() == TokenType.STRING_LITERAL;
    }

    /**
     * {@code policy HouseRules: "house-rules" checked by Compliance}, after the word {@code
     * policy}. The rules live in {@code policies/house-rules.md}, never in a prompt.
     */
    private io.github.llm4j.loom.ast.PolicyDef parsePolicy() {
        Token name = consume(TokenType.IDENTIFIER, "Expect a policy name.");
        consume(TokenType.COLON, "Expect ':' after policy " + name.getValue() + ".");
        Token file =
                consume(
                        TokenType.STRING_LITERAL,
                        "Expect the policy file, as in policy HouseRules: \"house-rules\".");
        String checkedBy = null;
        if (isWord("checked") && peekAt(1).getValue() != null && "by".equals(peekAt(1).getValue())) {
            advance();
            advance();
            checkedBy = word("Expect the evaluator's name after checked by.").getValue();
        }
        return new io.github.llm4j.loom.ast.PolicyDef(
                name.getValue(), file.getValue(), checkedBy, name.getLine());
    }

    /** Settings an evaluator can have; everything an agent has beyond these it cannot. */
    private static final java.util.List<String> EVALUATOR_SETTINGS =
            java.util.List.of(
                    "model", "unsure", "samples", "pii", "fallback", "timeout", "budget", "false_alarm_limit");

    /**
     * {@code evaluator Name { model: "…" unsure below 0.8 samples: 3 pii: mask fallback: ["…"] }},
     * after the word {@code evaluator}.
     */
    private io.github.llm4j.loom.ast.EvaluatorDef parseEvaluator() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect an evaluator name, e.g. evaluator Compliance { model: \"…\" }");
        io.github.llm4j.loom.ast.EvaluatorDef def =
                new io.github.llm4j.loom.ast.EvaluatorDef(name.getValue());
        def.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after evaluator " + name.getValue() + ".");
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key =
                    word(
                            "Expect an evaluator setting: "
                                    + String.join(", ", EVALUATOR_SETTINGS)
                                    + ".");
            String setting = key.getValue();
            if (EXTRACTOR_FORBIDDEN.contains(setting) || "instructions".equals(setting))
                throw error(
                        key,
                        "an evaluator is stateless and its instructions belong to the runtime; "
                                + setting
                                + " is not something an evaluator can have.");
            if (!EVALUATOR_SETTINGS.contains(setting)) {
                java.util.List<String> near =
                        io.github.llm4j.loom.prompt.PromptCatalog.nearest(
                                setting, EVALUATOR_SETTINGS);
                throw error(
                        key,
                        "\""
                                + setting
                                + "\" is not something an evaluator can have. An evaluator can have: "
                                + String.join(", ", EVALUATOR_SETTINGS)
                                + "."
                                + (near.isEmpty()
                                        ? ""
                                        : " Did you mean " + String.join(" or ", near) + "?"));
            }
            if (!seen.add(setting))
                throw error(key, setting + " is given twice in evaluator " + name.getValue() + ".");
            switch (setting) {
                case "model" -> {
                    consume(TokenType.COLON, "Expect ':' after model.");
                    def.setModel(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Expect a quoted model, as in model: \"claude-haiku-5-5\".")
                                    .getValue());
                }
                case "unsure" -> {
                    if (!isWord("below"))
                        throw error(peek(), "Write unsure below 0.8 (a number from 0 to 1).");
                    advance();
                    double below =
                            Double.parseDouble(
                                    consume(
                                                    TokenType.NUMBER_LITERAL,
                                                    "Expect a number from 0 to 1 after unsure below.")
                                            .getValue());
                    if (below < 0 || below > 1)
                        throw error(previous(), "unsure below needs a number from 0 to 1.");
                    def.setUnsureBelow(below);
                }
                case "samples" -> {
                    consume(TokenType.COLON, "Expect ':' after samples.");
                    int n =
                            Integer.parseInt(
                                    consume(
                                                    TokenType.NUMBER_LITERAL,
                                                    "Expect a whole number, as in samples: 3.")
                                            .getValue());
                    if (n < 1)
                        throw error(previous(), "samples: needs 1 or more.");
                    def.setSamples(n);
                }
                case "false_alarm_limit" -> {
                    consume(TokenType.COLON, "Expect ':' after false_alarm_limit.");
                    double limit =
                            Double.parseDouble(
                                    consume(
                                                    TokenType.NUMBER_LITERAL,
                                                    "Expect a share such as 10%, as in false_alarm_limit: 10%.")
                                            .getValue());
                    if (match(TokenType.PERCENT) || limit > 1) limit = limit / 100.0;
                    if (limit < 0 || limit > 1)
                        throw error(previous(), "false_alarm_limit needs a share from 0% to 100%.");
                    def.setFalseAlarmLimit(limit);
                }
                case "pii" -> {
                    consume(TokenType.COLON, "Expect ':' after pii.");
                    def.setPii(word("Expect mask, block or warn after pii:.").getValue());
                }
                case "fallback" -> {
                    consume(TokenType.COLON, "Expect ':' after fallback.");
                    if (match(TokenType.LBRACKET)) {
                        if (!check(TokenType.RBRACKET)) {
                            do {
                                def.getFallback()
                                        .add(
                                                consume(
                                                                TokenType.STRING_LITERAL,
                                                                "Expect a quoted model in fallback: [ … ].")
                                                        .getValue());
                            } while (match(TokenType.COMMA));
                        }
                        consume(TokenType.RBRACKET, "Expect ']' after the fallback models.");
                    } else {
                        def.getFallback()
                                .add(
                                        consume(
                                                        TokenType.STRING_LITERAL,
                                                        "Expect a quoted model, or a list of them, after fallback:.")
                                                .getValue());
                    }
                }
                case "timeout" -> {
                    consume(TokenType.COLON, "Expect ':' after timeout.");
                    def.setTimeoutMillis(durationMillis());
                }
                default -> def.setBudget(parseBudgetBlock(false)); // budget { … }
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after evaluator " + name.getValue() + ".");
        if (def.getModel() == null)
            throw error(
                    name,
                    "evaluator "
                            + name.getValue()
                            + " needs a model, as in model: \"claude-haiku-5-5\".");
        return def;
    }

    /** Agent settings an extractor is stateless without (docs/specs/24, R1). */
    private static final java.util.Set<String> EXTRACTOR_FORBIDDEN =
            java.util.Set.of(
                    "tools",
                    "memory",
                    "steerable",
                    "persona",
                    "skills",
                    "grounding",
                    "max_iterations",
                    "context",
                    "approve");

    /**
     * {@code extractor Name { model: "…" instructions: "…" fields { a: string } optional: [a] pii:
     * mask }}, after the word {@code extractor}. The words inside are contextual.
     */
    private io.github.llm4j.loom.ast.ExtractorDef parseExtractor() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect an extractor name, e.g. extractor Invoice { model: \"…\"  fields { total: number } }");
        io.github.llm4j.loom.ast.ExtractorDef def =
                new io.github.llm4j.loom.ast.ExtractorDef(name.getValue());
        def.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after extractor " + name.getValue() + ".");
        java.util.Set<String> seen = new java.util.HashSet<>();
        Token optionalAt = null;
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key =
                    word(
                            "Expect an extractor setting: "
                                    + String.join(", ", EXTRACTOR_SETTINGS)
                                    + ".");
            String setting = key.getValue();
            if (EXTRACTOR_FORBIDDEN.contains(setting))
                throw error(
                        key,
                        "an extractor is stateless; use an agent for this. "
                                + setting
                                + " is not something an extractor can have.");
            if (!EXTRACTOR_SETTINGS.contains(setting)) {
                java.util.List<String> near =
                        io.github.llm4j.loom.prompt.PromptCatalog.nearest(
                                setting, EXTRACTOR_SETTINGS);
                throw error(
                        key,
                        "\""
                                + setting
                                + "\" is not something an extractor can have. An extractor can have: "
                                + String.join(", ", EXTRACTOR_SETTINGS)
                                + "."
                                + (near.isEmpty()
                                        ? ""
                                        : " Did you mean " + String.join(" or ", near) + "?"));
            }
            if (!seen.add(setting))
                throw error(key, setting + " is given twice in extractor " + name.getValue() + ".");
            switch (setting) {
                case "model" -> {
                    consume(TokenType.COLON, "Expect ':' after model.");
                    def.setModel(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Expect a quoted model, as in model: \"gemini-2.5-flash\".")
                                    .getValue());
                }
                case "instructions" -> {
                    consume(TokenType.COLON, "Expect ':' after instructions.");
                    if (isWord("prompt") && peekAt(1).getType() == TokenType.STRING_LITERAL) {
                        advance();
                        def.setPromptRef(advance().getValue());
                    } else {
                        def.setInstructions(
                                consume(
                                                TokenType.STRING_LITERAL,
                                                "Write instructions: \"fixed text\" or instructions: prompt \"id\" (a prompt file).")
                                        .getValue());
                    }
                }
                case "fields" -> def.setFields(parseExtractorFields(name));
                case "optional" -> {
                    optionalAt = key;
                    consume(TokenType.COLON, "Expect ':' after optional.");
                    consume(TokenType.LBRACKET, "Expect [field, …] after optional:.");
                    if (!check(TokenType.RBRACKET)) {
                        do {
                            def.getOptional()
                                    .add(
                                            word("Expect a field name in optional: [ … ].")
                                                    .getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after the optional fields.");
                }
                case "pii" -> {
                    consume(TokenType.COLON, "Expect ':' after pii.");
                    def.setPii(word("Expect mask, block or warn after pii:.").getValue());
                }
                case "media" -> {
                    consume(TokenType.COLON, "Expect ':' after media.");
                    Token value = word("Write media: unscanned.");
                    if (!"unscanned".equals(value.getValue()))
                        throw error(
                                value,
                                "media takes one value, as in media: unscanned (it accepts sending"
                                        + " pictures, sound, video or documents that pii: cannot"
                                        + " inspect), not "
                                        + value.getValue()
                                        + ".");
                    def.setUnscannedMedia(true);
                }
                case "temperature" -> {
                    consume(TokenType.COLON, "Expect ':' after temperature.");
                    def.setTemperature(
                            Double.parseDouble(
                                    consume(
                                                    TokenType.NUMBER_LITERAL,
                                                    "Expect a number, as in temperature: 0.")
                                            .getValue()));
                }
                case "timeout" -> {
                    consume(TokenType.COLON, "Expect ':' after timeout.");
                    def.setTimeoutMillis(durationMillis());
                }
                default -> def.setBudget(parseBudgetBlock(false)); // budget { … }
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after extractor " + name.getValue() + ".");
        if (def.getModel() == null)
            throw error(
                    name,
                    "extractor "
                            + name.getValue()
                            + " needs a model, as in model: \"gemini-2.5-flash\".");
        if (def.getFields() == null)
            throw error(
                    name,
                    "extractor "
                            + name.getValue()
                            + " needs fields, as in fields { vendor: string  total: number }.");
        for (String optional : def.getOptional()) {
            if (!def.getFields().getFields().containsKey(optional))
                throw error(
                        optionalAt,
                        "optional: "
                                + optional
                                + " is not a field of extractor "
                                + name.getValue()
                                + "; its fields are "
                                + String.join(", ", def.getFields().getFields().keySet())
                                + ".");
        }
        return def;
    }

    /** {@code fields { a: string b: number }}, after the word {@code fields}. */
    private SchemaDef parseExtractorFields(Token extractorName) {
        consume(TokenType.LBRACE, "Expect '{' after fields.");
        SchemaDef schema = new SchemaDef(SchemaDef.Type.OBJECT);
        java.util.Map<String, SchemaDef> fields = new java.util.LinkedHashMap<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token field = advance();
            if (field.getValue() == null || !field.getValue().matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw error(field, "Expect a field name, as in fields { total: number }.");
            consume(TokenType.COLON, "Expect ':' after " + field.getValue() + ".");
            if (fields.containsKey(field.getValue()))
                throw error(
                        field,
                        field.getValue()
                                + " is written twice in the fields of extractor "
                                + extractorName.getValue()
                                + ".");
            fields.put(field.getValue(), parseSchema());
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after the fields.");
        if (fields.isEmpty())
            throw error(
                    previous(),
                    "extractor "
                            + extractorName.getValue()
                            + " needs at least one field, as in fields { total: number }.");
        schema.setFields(fields);
        return schema;
    }

    /**
     * {@code extract Invoice from text -> invoice [retry 1] [backoff 2s] [timeout 30s] [budget N
     * tokens] [on_failure { … }]} or {@code extract Claim from (email = msg, order = record) ->
     * claim}.
     */
    /**
     * Whether an {@code extract} input is a file: a kind word followed by a quoted file, as in
     * {@code image "{receipt}"}. A kind word alone is still a variable of that name.
     */
    private boolean atMediaInput() {
        return check(TokenType.IDENTIFIER)
                && mediaKindOf(peek().getValue()) != null
                && peekAt(1).getType() == TokenType.STRING_LITERAL;
    }

    private AttachItem parseMediaInput() {
        Token kind = advance();
        Token file = consume(TokenType.STRING_LITERAL, "Write the file after " + kind.getValue() + ".");
        return new AttachItem(mediaKindOf(kind.getValue()), file.getValue(), kind.getLine());
    }

    private io.github.llm4j.loom.ast.ExtractStmt parseExtract() {
        Token keyword = advance();
        Token extractor = consume(TokenType.IDENTIFIER, "Expect the name of an extractor.");
        advance(); // from
        java.util.List<RunStmt.Arg> inputs = new java.util.ArrayList<>();
        java.util.List<ExtractMedia> media = new java.util.ArrayList<>();
        String written = "(…)";
        if (match(TokenType.LPAREN)) {
            while (!check(TokenType.RPAREN) && !isAtEnd()) {
                Token field =
                        word("Expect an input name, as in from (email = msg, order = record).");
                consume(TokenType.ASSIGN, "Expect '=' after " + field.getValue() + ".");
                if (atMediaInput()) media.add(new ExtractMedia(field.getValue(), parseMediaInput()));
                else inputs.add(classifierInput(field.getValue()));
                match(TokenType.COMMA);
            }
            consume(TokenType.RPAREN, "Expect ')' after the inputs.");
            if (inputs.isEmpty() && media.isEmpty())
                throw error(previous(), "Name at least one input, as in from (email = msg).");
        } else if (atMediaInput()) {
            AttachItem item = parseMediaInput();
            media.add(new ExtractMedia("", item));
            written = item.kind().label() + " \"" + item.path() + "\"";
        } else {
            inputs.add(classifierInput(""));
            written = inputs.get(0).text();
        }
        consume(
                TokenType.ARROW,
                "Expect '->' and a variable to hold the fields, as in extract "
                        + extractor.getValue()
                        + " from "
                        + written
                        + " -> result.");
        String variable = nameOrReference("Expect a variable name for the fields.");
        io.github.llm4j.loom.ast.ExtractStmt stmt =
                new io.github.llm4j.loom.ast.ExtractStmt(extractor.getValue(), variable);
        stmt.setLine(keyword.getLine());
        stmt.getInputs().addAll(inputs);
        stmt.getMediaInputs().addAll(media);
        while (true) {
            if (match(TokenType.RETRY)) {
                stmt.setRetryCount(
                        (int)
                                Double.parseDouble(
                                        consume(
                                                        TokenType.NUMBER_LITERAL,
                                                        "Expect number of retries.")
                                                .getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue())
                            || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis);
                else stmt.setTimeoutMillis(millis);
            } else if (isBudgetModifier()) {
                stmt.setBudget(parseBudgetModifier(stmt.getBudget()));
            } else {
                break;
            }
        }
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd())
                stmt.getOnFailure().add(parseStatement());
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    /**
     * {@code check reply against HouseRules, Legal using Compliance -> verdict [retry N] [timeout
     * T] [on_violation { action }] [on_unavailable { action }] [on_failure { … }]}.
     */
    private io.github.llm4j.loom.ast.CheckStmt parseCheck() {
        Token keyword = advance();
        Token subject = word("Expect the variable to check.");
        advance(); // against
        java.util.List<String> policies = new java.util.ArrayList<>();
        do {
            policies.add(
                    consume(
                                    TokenType.IDENTIFIER,
                                    "Expect a policy name after against, as in check reply against HouseRules using Compliance.")
                            .getValue());
        } while (match(TokenType.COMMA));
        if (!isWord("using"))
            throw error(
                    peek(),
                    "Name the evaluator: check "
                            + subject.getValue()
                            + " against "
                            + String.join(", ", policies)
                            + " using <Evaluator>.");
        advance();
        Token evaluator = consume(TokenType.IDENTIFIER, "Expect an evaluator name after using.");
        io.github.llm4j.loom.ast.CheckStmt stmt =
                new io.github.llm4j.loom.ast.CheckStmt(subject.getValue(), evaluator.getValue());
        stmt.setLine(keyword.getLine());
        stmt.getPolicies().addAll(policies);
        if (match(TokenType.ARROW))
            stmt.setVerdictVariable(nameOrReference("Expect a variable name for the verdict."));
        boolean violationSeen = false;
        boolean unavailableSeen = false;
        while (true) {
            if (match(TokenType.RETRY)) {
                stmt.setRetryCount(
                        (int)
                                Double.parseDouble(
                                        consume(
                                                        TokenType.NUMBER_LITERAL,
                                                        "Expect number of retries.")
                                                .getValue()));
            } else if (check(TokenType.IDENTIFIER) && "timeout".equals(peek().getValue())) {
                advance();
                stmt.setTimeoutMillis(durationMillis());
            } else if (check(TokenType.ON_VIOLATION)) {
                if (violationSeen) throw error(peek(), "on_violation is given twice.");
                violationSeen = true;
                advance();
                stmt.setOnViolation(parseCheckAction("on_violation", true));
            } else if (isWord("on_unavailable")) {
                if (unavailableSeen) throw error(peek(), "on_unavailable is given twice.");
                unavailableSeen = true;
                advance();
                stmt.setOnUnavailable(parseCheckAction("on_unavailable", false));
            } else {
                break;
            }
        }
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd())
                stmt.getOnFailure().add(parseStatement());
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    /** {@code { rewrite with Agent }}, {@code { ask person }}, {@code { replace with "…" }} or {@code { fail }}: exactly one. */
    private io.github.llm4j.loom.ast.CheckStmt.Action parseCheckAction(
            String where, boolean allowRewrite) {
        consume(TokenType.LBRACE, "Expect '{' after " + where + ".");
        io.github.llm4j.loom.ast.CheckStmt.Action action;
        if (isWord("rewrite")) {
            Token at = advance();
            if (!allowRewrite)
                throw error(at, where + " cannot rewrite: no evaluator answered, so there is nothing to fix.");
            if (!isWord("with")) throw error(peek(), "Write rewrite with <Agent>.");
            advance();
            action =
                    new io.github.llm4j.loom.ast.CheckStmt.Rewrite(
                            consume(TokenType.IDENTIFIER, "Expect an agent name after rewrite with.")
                                    .getValue());
        } else if (isWord("ask")) {
            advance();
            if (!isWord("person")) throw error(peek(), "Write ask person.");
            advance();
            action = new io.github.llm4j.loom.ast.CheckStmt.AskPerson();
        } else if (isWord("replace")) {
            advance();
            if (!isWord("with")) throw error(peek(), "Write replace with \"fixed text\".");
            advance();
            action =
                    new io.github.llm4j.loom.ast.CheckStmt.Replace(
                            consume(
                                            TokenType.STRING_LITERAL,
                                            "Expect the replacement text in quotes.")
                                    .getValue());
        } else if (isWord("fail")) {
            advance();
            action = new io.github.llm4j.loom.ast.CheckStmt.Fail();
        } else {
            throw error(
                    peek(),
                    where
                            + " takes exactly one action: "
                            + (allowRewrite ? "rewrite with <Agent>, " : "")
                            + "ask person, replace with \"…\" or fail.");
        }
        if (!check(TokenType.RBRACE))
            throw error(peek(), where + " takes exactly one action; remove the others.");
        consume(TokenType.RBRACE, "Expect '}' after the action.");
        return action;
    }

    /** One thing to classify: a quoted text (with {placeholders}) or a variable. */
    private RunStmt.Arg classifierInput(String field) {
        if (check(TokenType.STRING_LITERAL))
            return new RunStmt.Arg(field, RunStmt.Kind.STRING, advance().getValue());
        Token ref =
                word(
                        "Expect what to classify: a variable (message, request.text) or a quoted text.");
        return new RunStmt.Arg(field, RunStmt.Kind.REFERENCE, ref.getValue());
    }

    private PersonaDef parsePersonaDef() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect a persona name, e.g. persona Mentor { role: \"…\" }");
        PersonaDef persona = new PersonaDef(name.getValue());
        persona.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after persona " + name.getValue() + ".");
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key =
                    word(
                            "Expect a persona field: role, expertise, tone, description or constraints");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (!seen.add(key.getValue()))
                throw error(key, key.getValue() + " is given twice in persona " + name.getValue());
            switch (key.getValue()) {
                case "role" -> persona.setRole(personaText(key));
                case "expertise" -> persona.setExpertise(personaText(key));
                case "tone" -> persona.setTone(personaText(key));
                case "description" -> persona.setDescription(personaText(key));
                case "constraints" -> {
                    consume(TokenType.LBRACKET, "Expect [\"…\", …] after constraints:");
                    if (!check(TokenType.RBRACKET)) {
                        do {
                            persona.getConstraints()
                                    .add(
                                            consume(
                                                            TokenType.STRING_LITERAL,
                                                            "Expect a constraint (string).")
                                                    .getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after constraints.");
                }
                default -> throw error(
                        key,
                        "unknown persona field "
                                + key.getValue()
                                + "; use role, expertise, tone, description or constraints");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after persona " + name.getValue() + ".");
        if (persona.getRole() == null)
            throw error(name, "persona " + name.getValue() + " needs role: \"…\"");
        return persona;
    }

    private String personaText(Token key) {
        return consume(TokenType.STRING_LITERAL, "Expect a string after " + key.getValue() + ":")
                .getValue();
    }

    private GuardrailStmt parseGuardrailStatement() {
        consume(TokenType.LPAREN, "Expect '(' after guardrail.");
        Token typeToken = consume(TokenType.IDENTIFIER, "Expect guardrail type (e.g. PII).");
        consume(TokenType.RPAREN, "Expect ')'.");

        GuardrailStmt stmt = new GuardrailStmt(typeToken.getValue());
        stmt.setLine(typeToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before guardrail body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            stmt.addBodyStatement(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after guardrail body.");

        if (match(TokenType.ON_VIOLATION)) {
            consume(TokenType.LBRACE, "Expect '{' before on_violation body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                stmt.addViolationStatement(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_violation body.");
        }

        return stmt;
    }

    private ScheduleDef parseSchedule() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect schedule name.");
        ScheduleDef sd = new ScheduleDef(nameToken.getValue());
        sd.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before schedule body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.PATTERN)) {
                consume(TokenType.COLON, "Expect ':' after pattern.");
                sd.setPattern(
                        consume(TokenType.STRING_LITERAL, "Expect pattern string.").getValue());
            } else if (match(TokenType.AGENT)) {
                consume(TokenType.COLON, "Expect ':' after agent.");
                sd.setAgentName(consume(TokenType.IDENTIFIER, "Expect agent name.").getValue());
            } else if (payloadMatch()) { // Generic lookup for task
                advance(); // consume 'task'
                consume(TokenType.COLON, "Expect ':' after key.");
                sd.setTask(consume(TokenType.STRING_LITERAL, "Expect task string.").getValue());
            } else if (match(TokenType.PATH)) { // Reuse path for delay? or just identifier
                consume(TokenType.COLON, "Expect ':'.");
                sd.setInitialDelay(
                        consume(TokenType.STRING_LITERAL, "Expect delay string.").getValue());
            } else if (peek().getType() == TokenType.IDENTIFIER) {
                Token keyToken = advance();
                String key = keyToken.getValue();
                consume(TokenType.COLON, "Expect ':' after key.");
                switch (key) {
                    case "cron" -> {
                        Token v =
                                consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a cron string, e.g. cron: \"0 7 * * *\"");
                        try {
                            io.github.llm4j.loom.trigger.CronSchedule.parse(v.getValue());
                        } catch (IllegalArgumentException e) {
                            throw error(v, e.getMessage());
                        }
                        sd.setCron(v.getValue());
                    }
                    case "every" -> sd.setEvery(duration(keyToken));
                    case "timezone" -> {
                        Token v =
                                consume(
                                        TokenType.STRING_LITERAL,
                                        "Expect a time zone string, e.g. timezone: \"Asia/Kolkata\"");
                        try {
                            java.time.ZoneId.of(v.getValue());
                        } catch (java.time.DateTimeException e) {
                            throw error(v, "Unknown time zone '" + v.getValue() + "'");
                        }
                        sd.setTimezone(v.getValue());
                    }
                    case "run" -> {
                        sd.setRunWorkflow(
                                consume(
                                                TokenType.IDENTIFIER,
                                                "Expect a workflow to run, e.g. run: Digest()")
                                        .getValue());
                        consume(TokenType.LPAREN, "Expect '(' after the workflow name.");
                        if (!check(TokenType.RPAREN)) {
                            do {
                                Token argName =
                                        consume(TokenType.IDENTIFIER, "Expect argument name.");
                                consume(TokenType.ASSIGN, "Expect '=' after argument name.");
                                sd.getRunArgs()
                                        .put(
                                                argName.getValue(),
                                                consume(
                                                                TokenType.STRING_LITERAL,
                                                                "Expect a string argument value.")
                                                        .getValue());
                            } while (match(TokenType.COMMA));
                        }
                        consume(TokenType.RPAREN, "Expect ')' after run arguments.");
                    }
                    case "misfire" -> {
                        Token v =
                                consume(
                                        TokenType.IDENTIFIER,
                                        "Expect run_once or skip after misfire.");
                        if (!v.getValue().equals("run_once") && !v.getValue().equals("skip")) {
                            throw error(
                                    v,
                                    "misfire must be run_once or skip, got '" + v.getValue() + "'");
                        }
                        sd.setMisfire(v.getValue());
                    }
                    case "overlap" -> {
                        Token v =
                                consume(
                                        TokenType.IDENTIFIER,
                                        "Expect skip or queue after overlap.");
                        if (!v.getValue().equals("skip") && !v.getValue().equals("queue")) {
                            throw error(
                                    v, "overlap must be skip or queue, got '" + v.getValue() + "'");
                        }
                        sd.setOverlap(v.getValue());
                    }
                    case "task" -> sd.setTask(
                            consume(TokenType.STRING_LITERAL, "Expect string value.").getValue());
                    case "initial_delay" -> sd.setInitialDelay(
                            consume(TokenType.STRING_LITERAL, "Expect string value.").getValue());
                    default -> throw error(
                            keyToken,
                            "Unknown schedule field '"
                                    + key
                                    + "'. Use cron, every, timezone, run, misfire, overlap, agent, task, pattern or initial_delay.");
                }
            } else {
                throw error(peek(), "Unexpected token in schedule body: " + peek().getType());
            }
        }
        Token close = consume(TokenType.RBRACE, "Expect '}' after schedule body.");
        if (sd.getCron() != null && (sd.getEvery() != null || sd.getPattern() != null)) {
            throw error(nameToken, "schedule " + sd.getName() + ": use cron or every, not both");
        }
        if (sd.getRunWorkflow() != null && sd.getAgentName() != null) {
            throw error(
                    nameToken,
                    "schedule "
                            + sd.getName()
                            + ": use run (a workflow) or agent + task, not both");
        }
        if (sd.getRunWorkflow() == null && sd.getAgentName() == null) {
            throw error(
                    close,
                    "schedule " + sd.getName() + " needs run: <Workflow>(...) or agent + task");
        }
        if (sd.getCron() == null
                && sd.getEvery() == null
                && sd.getPattern() == null
                && sd.getRunWorkflow() != null) {
            throw error(
                    close,
                    "schedule " + sd.getName() + " needs cron: \"...\" or every: <duration>");
        }
        return sd;
    }

    private ParallelStmt parseParallelStatement() {
        ParallelStmt stmt = new ParallelStmt();
        consume(TokenType.LBRACE, "Expect '{' after parallel.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            stmt.addStatement(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after parallel body.");
        return stmt;
    }

    private ObserveStmt parseObserveStatement() {
        Token labelToken = consume(TokenType.STRING_LITERAL, "Expect observation label.");
        consume(TokenType.LBRACE, "Expect '{' before expression.");
        // We'll read everything until next '}' as the expression
        StringBuilder expression = new StringBuilder();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            expression.append(advance().getValue());
            if (!check(TokenType.RBRACE)) expression.append(" ");
        }
        consume(TokenType.RBRACE, "Expect '}' after expression.");
        return new ObserveStmt(labelToken.getValue(), expression.toString().trim());
    }

    private boolean payloadMatch() {
        return peek().getType() == TokenType.IDENTIFIER && peek().getValue().equals("task");
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().getType() == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().getType() == TokenType.EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    // ── Tools ────────────────────────────────────────────────────────────────────────────────

    /** {@code tool Name { use: kind option: value … }} — after the keyword. */
    private io.github.llm4j.loom.ast.ToolDef parseToolDef() {
        Token name =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect a tool name, e.g. tool Search { use: duckduckgo }");
        io.github.llm4j.loom.ast.ToolDef tool =
                new io.github.llm4j.loom.ast.ToolDef(name.getValue());
        tool.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after tool " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            // A quoted key lets option names hold characters a bare word can't:
            // "header.X-Trace-Id": "abc"
            Token key =
                    check(TokenType.STRING_LITERAL)
                            ? advance()
                            : word("Expect a tool option, e.g. use: serpapi");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (key.getValue().equals("use")) {
                tool.setKind(
                        word("Expect a tool kind after use:, e.g. use: duckduckgo").getValue());
            } else if (key.getValue().equals("effect")) {
                if (tool.getEffect().isPresent()) throw error(key, "option effect is given twice");
                tool.setEffect(effectWord());
            } else if (key.getValue().equals("effects")) {
                parseEffectMap(tool.getName(), tool::putOperationEffect);
            } else {
                if (tool.getOptions().containsKey(key.getValue()))
                    throw error(key, "option " + key.getValue() + " is given twice");
                tool.getOptions().put(key.getValue(), optionValue());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after tool " + name.getValue() + ".");
        if (tool.getKind() == null)
            throw error(name, "tool " + name.getValue() + " needs use: <kind>");
        if (tool.getEffect().isPresent() && tool.getOptions().containsKey("reach"))
            throw error(
                    name,
                    "tool "
                            + name.getValue()
                            + " has both effect: and reach:. Keep effect: (none, reads or changes);"
                            + " reach: is the older form and says less.");
        return tool;
    }

    /** {@code none}, {@code reads} or {@code changes}, after {@code effect:}. */
    private io.github.llm4j.agent.task.TaskEffect effectWord() {
        Token w = word("Expect none, reads or changes after effect:");
        return switch (w.getValue()) {
            case "none" -> io.github.llm4j.agent.task.TaskEffect.NONE;
            case "reads" -> io.github.llm4j.agent.task.TaskEffect.READS;
            case "changes" -> io.github.llm4j.agent.task.TaskEffect.CHANGES;
            default -> throw error(
                    w,
                    "effect must be none, reads or changes, not "
                            + w.getValue()
                            + " (none: touches nothing outside the run; reads: looks something up;"
                            + " changes: writes, sends, pays or runs a program)");
        };
    }

    /** {@code { operation: reads, other: changes }}, after {@code effects:}. */
    private void parseEffectMap(
            String owner,
            java.util.function.BiConsumer<String, io.github.llm4j.agent.task.TaskEffect> into) {
        consume(TokenType.LBRACE, "Expect '{' after effects: in " + owner + ".");
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token op =
                    check(TokenType.STRING_LITERAL)
                            ? advance()
                            : word("Expect an operation name, e.g. effects: { listOrders: reads }");
            consume(TokenType.COLON, "Expect ':' after " + op.getValue() + ".");
            if (!seen.add(op.getValue()))
                throw error(op, "operation " + op.getValue() + " is given twice in effects:");
            into.accept(op.getValue(), effectWord());
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after effects: in " + owner + ".");
    }

    /** A bare word: an identifier or a keyword (option names like path, type, class). */
    private Token word(String message) {
        if (!isAtEnd()
                && peek().getValue() != null
                && peek().getValue().matches("[A-Za-z_][A-Za-z0-9_.]*")
                && peek().getType() != TokenType.STRING_LITERAL) {
            return advance();
        }
        throw error(peek(), message);
    }

    /**
     * A string, number, true/false, a word, or {@code env.NAME}, {@code secret.NAME} or {@code
     * invoker.NAME}.
     */
    private io.github.llm4j.loom.ast.ToolDef.OptionValue optionValue() {
        if (check(TokenType.NUMBER_LITERAL)) {
            Token number = advance();
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(
                    number.getValue() + unitSuffix(number));
        }
        if (check(TokenType.STRING_LITERAL)) {
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(advance().getValue());
        }
        Token w =
                word(
                        "Expect a value: a \"string\", a number, true/false, env.NAME, secret.NAME or invoker.NAME");
        if (w.getValue().startsWith("env.")) {
            String var = w.getValue().substring(4);
            if (var.isEmpty() || var.contains("."))
                throw error(w, "an environment reference is env.NAME, got " + w.getValue());
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.env(var);
        }
        if (w.getValue().startsWith("secret.")) {
            String name = w.getValue().substring(7);
            if (!io.github.llm4j.secret.SecretNames.isValid(name)) {
                throw error(
                        w,
                        "a secret reference is secret.NAME (a letter, then letters, digits, _ or -), got "
                                + w.getValue());
            }
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.secret(name);
        }
        if (w.getValue().startsWith("invoker.")) {
            String name = w.getValue().substring(8);
            if (!io.github.llm4j.secret.SecretNames.isValid(name)) {
                throw error(
                        w,
                        "an invoker reference is invoker.NAME (a letter, then letters, digits, _ or -), got "
                                + w.getValue());
            }
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.invoker(name);
        }
        return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(w.getValue());
    }

    private static final java.util.Set<String> UNITS =
            java.util.Set.of("ms", "s", "m", "h", "k", "kb", "mb");

    /**
     * A unit written straight after a number ({@code 20s}, {@code 64k}) belongs to it. A word that
     * is itself a key (followed by ':') is left alone.
     */
    private String unitSuffix(Token number) {
        if (isAtEnd()) return "";
        Token next = peek();
        boolean adjacent =
                next.getType() == TokenType.IDENTIFIER
                        && next.getLine() == number.getLine()
                        && next.getColumn() == number.getColumn() + number.getValue().length();
        boolean isKey =
                tokens.size() > current + 1 && tokens.get(current + 1).getType() == TokenType.COLON;
        if (adjacent
                && !isKey
                && UNITS.contains(next.getValue().toLowerCase(java.util.Locale.ROOT)))
            return advance().getValue();
        return "";
    }

    // ── Budgets ──────────────────────────────────────────────────────────────────────────────

    private boolean isBudgetKeyword() {
        return check(TokenType.IDENTIFIER)
                && "budget".equals(peek().getValue())
                && tokens.size() > current + 1
                && tokens.get(current + 1).getType() == TokenType.LBRACE;
    }

    /** {@code budget} followed by a number or a string: a statement's budget modifier. */
    private boolean isBudgetModifier() {
        if (!check(TokenType.IDENTIFIER)
                || !"budget".equals(peek().getValue())
                || tokens.size() <= current + 1) return false;
        TokenType next = tokens.get(current + 1).getType();
        return next == TokenType.NUMBER_LITERAL || next == TokenType.STRING_LITERAL;
    }

    /** A contextual declaration keyword followed by a name and a block: {@code remote Name {}. */
    private boolean isDeclaration(String keyword) {
        return isWord(keyword)
                && peekAt(1).getType() == TokenType.IDENTIFIER
                && peekAt(2).getType() == TokenType.LBRACE;
    }

    /** {@code keyword Name { key: value … }}: a name and a settings block; a name may be declared once. */
    private <T extends io.github.llm4j.loom.ast.NamedSettings> T parseNamedSettings(
            java.util.function.Function<String, T> factory,
            String keyword,
            java.util.function.Predicate<String> alreadyDeclared) {
        Token keywordToken = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect a name after " + keyword + ".");
        if (alreadyDeclared.test(name.getValue()))
            throw error(name, keyword + " " + name.getValue() + " is declared twice.");
        return parseSettings(factory.apply(name.getValue()), keyword + " " + name.getValue(), keywordToken.getLine());
    }

    /**
     * {@code expose Name { workflow: W … skills: [ { id: "x" name: "y" } ] budget { … } }}: settings, plus a
     * list of skills and a budget block.
     */
    private io.github.llm4j.loom.ast.ExposeDef parseExpose(LoomScript script) {
        Token keywordToken = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect a name after expose.");
        if (script.getExposes().stream().anyMatch(e -> e.getName().equals(name.getValue())))
            throw error(name, "expose " + name.getValue() + " is declared twice.");
        io.github.llm4j.loom.ast.ExposeDef expose = new io.github.llm4j.loom.ast.ExposeDef(name.getValue());
        expose.setLine(keywordToken.getLine());
        consume(TokenType.LBRACE, "Expect '{' after expose " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect an expose setting, e.g. workflow: Expense");
            if (key.getValue().equals("budget") && check(TokenType.LBRACE)) {
                if (expose.getBudget() != null) throw error(key, "budget is given twice in expose");
                expose.setBudget(parseBudgetBlock(false));
                continue;
            }
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (expose.has(key.getValue()) || (key.getValue().equals("skills") && !expose.getSkills().isEmpty()))
                throw error(key, key.getValue() + " is given twice in expose");
            if (key.getValue().equals("skills")) {
                parseSkills(expose);
            } else {
                expose.put(key.getValue(), optionValue(), key.getLine());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after expose settings.");
        return expose;
    }

    private void parseSkills(io.github.llm4j.loom.ast.ExposeDef expose) {
        consume(TokenType.LBRACKET, "Write the skills in brackets: skills: [ { id: \"read\" name: \"Read a receipt\" } ]");
        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            Token open = consume(TokenType.LBRACE, "Write each skill in braces: { id: \"read\" name: \"Read a receipt\" }");
            String id = null;
            String skillName = null;
            String description = "";
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                Token key = word("Expect a skill setting: id, name or description");
                consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
                String value = consume(TokenType.STRING_LITERAL, "Write the " + key.getValue() + " of the skill in quotes.").getValue();
                switch (key.getValue()) {
                    case "id" -> id = value;
                    case "name" -> skillName = value;
                    case "description" -> description = value;
                    default -> throw error(key, "a skill has an id, a name and a description, not " + key.getValue() + ".");
                }
                match(TokenType.COMMA);
            }
            consume(TokenType.RBRACE, "Expect '}' after the skill.");
            if (id == null) throw error(open, "A skill needs an id, as in { id: \"read\" name: \"Read a receipt\" }.");
            expose.getSkills().add(new io.github.llm4j.loom.ast.ExposeDef.Skill(id, skillName == null ? id : skillName, description, open.getLine()));
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACKET, "Expect ']' after the skills.");
    }

    /** {@code ask Channel "question" -> reply [expecting { … }] [retry n] [backoff d] [timeout d] [on_failure { … }]}. */
    private io.github.llm4j.loom.ast.AskStmt parseAsk() {
        advance(); // ask
        Token channel = consume(TokenType.IDENTIFIER, "Name the agent_channel to ask, as in: ask Desk \"Which currency?\" -> reply");
        Token text = consume(TokenType.STRING_LITERAL, "Write the question in quotes, as in: ask " + channel.getValue() + " \"Which currency?\" -> reply");
        consume(TokenType.ARROW, "Expect '->' and a variable for the answer, as in: ask " + channel.getValue() + " \"" + text.getValue() + "\" -> reply");
        Token variable = consume(TokenType.IDENTIFIER, "Expect a variable name for the answer.");
        io.github.llm4j.loom.ast.AskStmt ask = new io.github.llm4j.loom.ast.AskStmt(channel.getValue(), text.getValue(), variable.getValue());
        if (check(TokenType.IDENTIFIER) && "expecting".equals(peek().getValue())) {
            advance();
            ask.setExpecting(parseSchema());
        }
        while (true) {
            if (match(TokenType.RETRY)) {
                Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
                ask.setRetryCount((int) Double.parseDouble(count.getValue()));
            } else if (check(TokenType.IDENTIFIER)
                    && ("backoff".equals(peek().getValue()) || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) ask.setBackoffMillis(millis);
                else ask.setTimeoutMillis(millis);
            } else {
                break;
            }
        }
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                ask.getOnFailure().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return ask;
    }

    /** A contextual keyword opening a block: {@code name {}. */
    private boolean isBlockKeyword(String name) {
        return check(TokenType.IDENTIFIER)
                && name.equals(peek().getValue())
                && tokens.size() > current + 1
                && tokens.get(current + 1).getType() == TokenType.LBRACE;
    }

    /**
     * {@code rate_limits { on_limit: suspend|wait|fail max_wait: 24h max_resumes: 50 }} — after the
     * keyword.
     */
    private io.github.llm4j.loom.ast.RateLimitDef parseRateLimits() {
        io.github.llm4j.loom.ast.RateLimitDef def = new io.github.llm4j.loom.ast.RateLimitDef();
        consume(TokenType.LBRACE, "Expect '{' after rate_limits.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token field =
                    consume(
                            TokenType.IDENTIFIER,
                            "Expect a rate_limits field: on_limit, max_wait or max_resumes.");
            consume(TokenType.COLON, "Expect ':' after '" + field.getValue() + "'.");
            switch (field.getValue()) {
                case "on_limit" -> {
                    Token v =
                            consume(
                                    TokenType.IDENTIFIER,
                                    "Expect suspend, wait or fail after on_limit.");
                    try {
                        def.setOnLimit(
                                io.github.llm4j.loom.ast.RateLimitDef.OnLimit.valueOf(
                                        v.getValue().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw error(
                                v,
                                "on_limit must be suspend, wait or fail, got '"
                                        + v.getValue()
                                        + "'");
                    }
                }
                case "max_wait" -> def.setMaxWait(duration(field));
                case "max_resumes" -> def.setMaxResumes(
                        (int) Math.min(Integer.MAX_VALUE, positiveWhole(field)));
                default -> throw error(
                        field,
                        "Unknown rate_limits field '"
                                + field.getValue()
                                + "'. Use on_limit, max_wait or max_resumes.");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after rate_limits block.");
        return def;
    }

    /**
     * A positive duration: {@code 30s}, {@code 15m}, {@code 6h}, {@code 2d} (or the same as a
     * string).
     */
    private java.time.Duration duration(Token field) {
        Token n;
        String unit;
        if (check(TokenType.STRING_LITERAL)) {
            n = advance();
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\s*(\\d+)\\s*([smhd])\\s*")
                            .matcher(n.getValue());
            if (!m.matches())
                throw error(
                        n,
                        field.getValue()
                                + " must be a duration like 30s, 15m, 6h or 2d, got \""
                                + n.getValue()
                                + "\"");
            return durationOf(n, field, Long.parseLong(m.group(1)), m.group(2));
        }
        n =
                consume(
                        TokenType.NUMBER_LITERAL,
                        "Expect a duration for " + field.getValue() + ", e.g. 30s, 15m, 6h or 2d.");
        Token u =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect a unit (s, m, h or d) after "
                                + n.getValue()
                                + ", e.g. "
                                + n.getValue()
                                + "h.");
        unit = u.getValue();
        double value = Double.parseDouble(n.getValue());
        if (value != Math.floor(value))
            throw error(n, field.getValue() + " must be a whole number of " + unit);
        return durationOf(n, field, (long) value, unit);
    }

    private java.time.Duration durationOf(Token at, Token field, long amount, String unit) {
        if (amount <= 0) throw error(at, field.getValue() + " must be positive");
        return switch (unit) {
            case "s" -> java.time.Duration.ofSeconds(amount);
            case "m" -> java.time.Duration.ofMinutes(amount);
            case "h" -> java.time.Duration.ofHours(amount);
            case "d" -> java.time.Duration.ofDays(amount);
            default -> throw error(at, "Unknown duration unit '" + unit + "'. Use s, m, h or d.");
        };
    }

    /** Optional {@code per minute|hour|day} after a budget limit; one window per budget. */
    private void window(io.github.llm4j.loom.ast.BudgetDef budget) {
        if (!(check(TokenType.IDENTIFIER) && "per".equals(peek().getValue()))) return;
        advance();
        Token w = consume(TokenType.IDENTIFIER, "Expect minute, hour or day after 'per'.");
        io.github.llm4j.budget.Window window;
        try {
            window = io.github.llm4j.budget.Window.parse(w.getValue());
        } catch (IllegalArgumentException e) {
            throw error(
                    w,
                    "A budget window must be per minute, per hour or per day, got 'per "
                            + w.getValue()
                            + "'");
        }
        if (budget.getWindow() != null && budget.getWindow() != window) {
            throw error(
                    w,
                    "A budget has one window: it is already per "
                            + budget.getWindow().name().toLowerCase(java.util.Locale.ROOT));
        }
        budget.setWindow(window);
    }

    /**
     * {@code budget { tokens: N [per day] calls: N cost: "$X" warn_at: 80% per_call: N
     * when_exhausted: stop }} — after the keyword.
     */
    private io.github.llm4j.loom.ast.BudgetDef parseBudgetBlock(boolean forAgent) {
        io.github.llm4j.loom.ast.BudgetDef budget = new io.github.llm4j.loom.ast.BudgetDef();
        consume(TokenType.LBRACE, "Expect '{' after budget.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token field =
                    consume(
                            TokenType.IDENTIFIER,
                            "Expect a budget field: tokens, calls, searches, cost, warn_at, when_exhausted"
                                    + (forAgent ? " or per_call" : "")
                                    + ".");
            consume(TokenType.COLON, "Expect ':' after budget field '" + field.getValue() + "'.");
            switch (field.getValue()) {
                case "tokens" -> {
                    budget.setTokens(positiveWhole(field));
                    window(budget);
                }
                case "calls" -> {
                    budget.setCalls(positiveWhole(field));
                    window(budget);
                }
                case "searches" -> {
                    budget.setSearches(positiveWhole(field));
                    window(budget);
                }
                case "cost" -> {
                    budget.setCost(money(field));
                    window(budget);
                }
                case "when_exhausted" -> {
                    Token v =
                            consume(
                                    TokenType.IDENTIFIER,
                                    "Expect stop, suspend or ask after when_exhausted.");
                    try {
                        budget.setWhenExhausted(
                                io.github.llm4j.loom.ast.BudgetDef.WhenExhausted.valueOf(
                                        v.getValue().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw error(
                                v,
                                "when_exhausted must be stop, suspend or ask, got '"
                                        + v.getValue()
                                        + "'");
                    }
                }
                case "per_call" -> {
                    if (!forAgent)
                        throw error(field, "per_call is only allowed in an agent's budget block.");
                    budget.setPerCall((int) Math.min(Integer.MAX_VALUE, positiveWhole(field)));
                }
                case "warn_at" -> {
                    Token n =
                            consume(
                                    TokenType.NUMBER_LITERAL,
                                    "Expect a percentage for warn_at, e.g. warn_at: 80%");
                    double value = Double.parseDouble(n.getValue());
                    if (match(TokenType.PERCENT) || value > 1) value = value / 100.0;
                    if (value <= 0 || value > 1)
                        throw error(n, "warn_at must be between 1% and 100%, got " + n.getValue());
                    budget.setWarnAt(value);
                }
                default -> throw error(
                        field,
                        "Unknown budget field '"
                                + field.getValue()
                                + "'. Use tokens, calls, searches, cost, warn_at, when_exhausted"
                                + (forAgent ? " or per_call" : "")
                                + ".");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after budget block.");
        if (budget.getWhenExhausted() == io.github.llm4j.loom.ast.BudgetDef.WhenExhausted.SUSPEND
                && budget.getWindow() == null) {
            throw error(
                    previous(),
                    "when_exhausted: suspend needs a budget that refills, e.g. tokens: 100000 per day");
        }
        return budget;
    }

    /**
     * {@code budget 5000 tokens}, {@code budget 10 calls} or {@code budget "$0.05"}, merged into
     * {@code into}.
     */
    private io.github.llm4j.loom.ast.BudgetDef parseBudgetModifier(
            io.github.llm4j.loom.ast.BudgetDef into) {
        Token keyword = advance(); // budget
        io.github.llm4j.loom.ast.BudgetDef budget =
                into != null ? into : new io.github.llm4j.loom.ast.BudgetDef();
        if (check(TokenType.STRING_LITERAL)) {
            budget.setCost(money(keyword));
            return budget;
        }
        long n = positiveWhole(keyword);
        Token unit =
                consume(
                        TokenType.IDENTIFIER,
                        "Expect 'tokens', 'calls' or 'searches' after the budget amount, e.g. budget 5000 tokens");
        switch (unit.getValue()) {
            case "tokens" -> budget.setTokens(n);
            case "calls" -> budget.setCalls(n);
            case "searches" -> budget.setSearches(n);
            default -> throw error(
                    unit,
                    "Expect 'tokens', 'calls' or 'searches' after the budget amount, got '"
                            + unit.getValue()
                            + "'");
        }
        return budget;
    }

    private long positiveWhole(Token field) {
        Token n =
                consume(
                        TokenType.NUMBER_LITERAL,
                        "Expect a whole number for budget " + field.getValue() + ".");
        double value = Double.parseDouble(n.getValue());
        if (value <= 0 || value != Math.floor(value)) {
            throw error(
                    n,
                    "budget "
                            + field.getValue()
                            + " must be a positive whole number, got "
                            + n.getValue());
        }
        return (long) value;
    }

    /** A cost written with its currency symbol, e.g. "$0.50". */
    private java.math.BigDecimal money(Token field) {
        Token s =
                consume(
                        TokenType.STRING_LITERAL,
                        "Expect a cost with its currency symbol, e.g. \"$0.50\".");
        String raw = s.getValue().strip();
        if (!raw.matches("[$€£₹¥]\\s*\\d+(\\.\\d+)?")) {
            throw error(
                    s,
                    "budget cost must include a currency symbol, e.g. \"$0.50\", got \""
                            + raw
                            + "\"");
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(raw.substring(1).strip());
        if (amount.signum() <= 0)
            throw error(s, "budget cost must be positive, got \"" + raw + "\"");
        return amount;
    }

    private RuntimeException error(Token token, String message) {
        return new RuntimeException(
                io.github.llm4j.loom.hints.KnownMistakes.withHint(
                        "Parse error at line "
                                + token.getLine()
                                + " col "
                                + token.getColumn()
                                + " ("
                                + token.getType()
                                + "): "
                                + message,
                        tokens,
                        tokens.indexOf(token)));
    }
}
