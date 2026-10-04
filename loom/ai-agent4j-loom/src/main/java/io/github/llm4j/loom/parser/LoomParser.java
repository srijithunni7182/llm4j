package io.github.llm4j.loom.parser;

import io.github.llm4j.loom.ast.*;
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
                script.addImport(parseImport());
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
            } else if (check(TokenType.PERSONA) && tokens.size() > current + 1
                    && tokens.get(current + 1).getType() == TokenType.IDENTIFIER) {
                advance();
                script.addPersona(parsePersonaDef());
            } else if (isBudgetKeyword()) {
                // Contextual keyword: `budget` stays usable as a variable name everywhere else.
                Token keyword = advance();
                if (script.getBudget() != null) throw error(keyword, "Only one top-level budget block is allowed.");
                script.setBudget(parseBudgetBlock(false));
            } else if (isDecisionStart()) {
                script.addDecision(parseDecision());
            } else if (isBlockKeyword("rate_limits")) {
                Token keyword = advance();
                if (script.getRateLimits() != null) throw error(keyword, "Only one rate_limits block is allowed.");
                script.setRateLimits(parseRateLimits());
            } else {
                throw error(peek(), "Expected 'agent', 'workflow', 'tool', 'provider', 'persona', 'mcp', 'audit', 'knowledge', 'routing', 'schedule', 'budget' or 'rate_limits' declaration, but got: " + peek().getType());
            }
        }

        return script;
    }

    private String parseImport() {
        Token pathToken = consume(TokenType.STRING_LITERAL, "Expect string literal for import path.");
        return pathToken.getValue();
    }

    private AgentDef parseAgent() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect agent name.");
        AgentDef agent = new AgentDef(nameToken.getValue());
        agent.setLine(nameToken.getLine());

        consume(TokenType.LBRACE, "Expect '{' before agent body.");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.MODEL)) {
                consume(TokenType.COLON, "Expect ':' after model.");
                Token modelToken = consume(TokenType.STRING_LITERAL, "Expect string literal for model.");
                agent.setModel(modelToken.getValue());
            } else if (match(TokenType.SYSTEM)) {
                consume(TokenType.COLON, "Expect ':' after system.");
                Token sysToken = consume(TokenType.STRING_LITERAL, "Expect string literal for system prompt.");
                agent.setSystemPrompt(sysToken.getValue());
            } else if (match(TokenType.SYSTEM_TEMPLATE)) {
                consume(TokenType.COLON, "Expect ':' after system_template.");
                Token tmpl = consume(TokenType.STRING_LITERAL, "Expect string literal for system_template id.");
                agent.setSystemTemplate(tmpl.getValue());
            } else if (match(TokenType.PERSONA)) {
                consume(TokenType.COLON, "Expect ':' after persona.");
                Token personaToken = check(TokenType.IDENTIFIER) ? advance()
                        : consume(TokenType.STRING_LITERAL, "Expect a persona name, e.g. persona: Mentor");
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
                        Token skillToken = consume(TokenType.STRING_LITERAL, "Expect skill URI (string).");
                        agent.addSkill(skillToken.getValue());
                    } while (match(TokenType.COMMA));
                }
                consume(TokenType.RBRACKET, "Expect ']' after skills list.");
            } else if (match(TokenType.MEMORY)) {
                int memoryLine = previous().getLine();
                match(TokenType.COLON);
                agent.setMemory(parseSettings(new AgentDef.MemoryConfig(), "memory", memoryLine));
            } else if (isSettingsBlock("voice")) {
                int line = advance().getLine();
                match(TokenType.COLON);
                agent.setVoice(parseSettings(new AgentDef.VoiceConfig(), "voice", line));
            } else if (isSettingsBlock("guard")) {
                int line = advance().getLine();
                match(TokenType.COLON);
                agent.setGuard(parseSettings(new AgentDef.GuardConfig(), "guard", line));
            } else if (match(TokenType.ROUTING)) {
                consume(TokenType.COLON, "Expect ':' after routing.");
                Token policyToken = consume(TokenType.IDENTIFIER, "Expect routing policy name.");
                agent.setRoutingPolicy(policyToken.getValue());
            } else if (match(TokenType.KNOWLEDGE)) {
                consume(TokenType.COLON, "Expect ':' after knowledge.");
                consume(TokenType.LBRACKET, "Expect '[' before knowledge list.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        Token kbToken = consume(TokenType.IDENTIFIER, "Expect knowledge base name.");
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
                            agent.getApprove().add(consume(TokenType.IDENTIFIER, "Expect a tool name.").getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after approve list.");
                }
            } else if (check(TokenType.IDENTIFIER) && "max_iterations".equals(peek().getValue())) {
                Token keyword = advance();
                consume(TokenType.COLON, "Expect ':' after max_iterations.");
                Token n = consume(TokenType.NUMBER_LITERAL, "Expect a whole number for max_iterations.");
                double v = Double.parseDouble(n.getValue());
                if (v < 1 || v != Math.floor(v)) {
                    throw error(keyword, "max_iterations must be a positive whole number, got " + n.getValue());
                }
                agent.setMaxIterations((int) v);
            } else if (check(TokenType.IDENTIFIER) && "temperature".equals(peek().getValue())) {
                // Contextual keyword, so existing scripts may still use "temperature" as a name elsewhere.
                Token keyword = advance();
                consume(TokenType.COLON, "Expect ':' after temperature.");
                Token value = consume(TokenType.NUMBER_LITERAL, "Expect a number for temperature, e.g. temperature: 0.7");
                double temperature = Double.parseDouble(value.getValue());
                if (temperature < 0.0 || temperature > 2.0) {
                    throw error(keyword, "temperature must be between 0.0 and 2.0, got " + value.getValue());
                }
                agent.setTemperature(temperature);
            } else if (isBudgetKeyword()) {
                Token keyword = advance();
                if (agent.getBudget() != null) throw error(keyword, "An agent can have only one budget block.");
                agent.setBudget(parseBudgetBlock(true));
            } else {
                throw error(peek(), "Unexpected token in agent body: " + peek().getType());
            }
        }

        consume(TokenType.RBRACE, "Expect '}' after agent body.");
        return agent;
    }

    private WorkflowDef parseWorkflow() {
        Token nameToken = consume(TokenType.IDENTIFIER, "Expect workflow name.");
        WorkflowDef workflow = new WorkflowDef(nameToken.getValue());

        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    Token param = consume(TokenType.IDENTIFIER, "Expect parameter name.");
                    workflow.addParameter(param.getValue());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "Expect ')' after parameters.");
        }

        consume(TokenType.LBRACE, "Expect '{' before workflow body.");

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            workflow.addStatement(parseStatement());
        }

        consume(TokenType.RBRACE, "Expect '}' after workflow body.");
        return workflow;
    }

    private Statement parseStatement() {
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
        } else if (isWord("checkpoint") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseCheckpoint();
        } else if (isWord("rewind") && peekAt(1).getType() == TokenType.TO) {
            return parseRewind();
        } else if (isWord("decide") && peekAt(1).getType() == TokenType.IDENTIFIER) {
            return parseDecide();
        } else if (isRunStart()) {
            return parseRun();
        }
        
        throw error(peek(), "Expected statement, got " + peek().getType());
    }

    // -----------------------------------------------------------------------
    // Checkpoints and rewinds: read as sentences, in a fixed order of phrases.
    // -----------------------------------------------------------------------

    /** True when the next token is the plain word {@code word} (these words are keywords only where a statement starts). */
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

    /** A word that can name a task or an argument: it looks like an identifier, whichever keyword the lexer made of it. */
    private static boolean isNameToken(Token t) {
        return t.getType() != TokenType.STRING_LITERAL && t.getType() != TokenType.NUMBER_LITERAL
                && t.getValue() != null && t.getValue().matches("[A-Za-z][A-Za-z0-9_-]*");
    }

    /** {@code run Name(}: `run` is a keyword only where a statement starts and a task name and '(' follow, so it stays a usable variable name. */
    private boolean isRunStart() {
        return isWord("run") && isNameToken(peekAt(1)) && peekAt(2).getType() == TokenType.LPAREN;
    }

    /**
     * {@code run Task(name = value, ...) -> variable [retry N] [backoff 2s] [timeout 30s] [on_failure { ... }]}.
     * A value is a variable path (passed with its type), a quoted string (placeholders filled in), a number or true/false.
     */
    private RunStmt parseRun() {
        Token keyword = advance();
        Token task = advance();
        consume(TokenType.LPAREN, "Expect '(' after the task name, as in run " + task.getValue() + "(name = value).");
        List<RunStmt.Arg> args = new java.util.ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            do {
                Token key = advance();
                if (!isNameToken(key)) throw error(key, "Expect an argument name, as in run " + task.getValue() + "(name = value).");
                consume(TokenType.ASSIGN, "Expect '=' after " + key.getValue() + ", as in " + key.getValue() + " = value.");
                for (RunStmt.Arg earlier : args) {
                    if (earlier.name().equals(key.getValue())) throw error(key, "Argument " + key.getValue() + " is given twice.");
                }
                Token value = advance();
                if (value.getType() == TokenType.STRING_LITERAL) {
                    args.add(new RunStmt.Arg(key.getValue(), RunStmt.Kind.STRING, value.getValue()));
                } else if (value.getType() == TokenType.NUMBER_LITERAL) {
                    args.add(new RunStmt.Arg(key.getValue(), RunStmt.Kind.NUMBER, value.getValue()));
                } else if (isNameToken(value) && ("true".equals(value.getValue()) || "false".equals(value.getValue()))) {
                    args.add(new RunStmt.Arg(key.getValue(), RunStmt.Kind.BOOLEAN, value.getValue()));
                } else if (isNameToken(value) || (value.getValue() != null && value.getValue().matches("[A-Za-z][A-Za-z0-9_.-]*"))) {
                    args.add(new RunStmt.Arg(key.getValue(), RunStmt.Kind.REFERENCE, value.getValue()));
                } else {
                    throw error(value, "Expect a value for " + key.getValue() + ": a variable (request.amount), a quoted string, a number or true/false.");
                }
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RPAREN, "Expect ')' after the arguments of " + task.getValue() + ".");
        consume(TokenType.ARROW, "Expect '->' and a variable to hold the result, as in run " + task.getValue() + "(...) -> result.");
        String variable = nameOrReference("Expect a variable name for the result (or {item.field}).");

        RunStmt stmt = new RunStmt(task.getValue(), variable);
        stmt.setLine(keyword.getLine());
        stmt.getArgs().addAll(args);
        // Optional, in any order: retry 3 · backoff 2s · timeout 30s
        while (true) {
            if (match(TokenType.RETRY)) {
                Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
                stmt.setRetryCount((int) Double.parseDouble(count.getValue()));
            } else if (check(TokenType.IDENTIFIER) && ("backoff".equals(peek().getValue()) || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis); else stmt.setTimeoutMillis(millis);
            } else if (isBudgetModifier()) {
                throw error(peek(), "A task spends no tokens, so it has no budget: remove 'budget' from run " + task.getValue() + ".");
            } else {
                break;
            }
        }
        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                stmt.getOnFailure().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }
        return stmt;
    }

    /** {@code checkpoint Name [starting with name = "value", name = "value"]} */
    private CheckpointStmt parseCheckpoint() {
        Token keyword = advance();
        CheckpointStmt stmt = new CheckpointStmt(consume(TokenType.IDENTIFIER, "Expect a name after checkpoint.").getValue());
        stmt.setLine(keyword.getLine());
        if (isWord("starting")) {
            advance();
            expectWord("with", "Write: checkpoint " + stmt.getName() + "  starting with name = \"value\"");
            parseAssignments(stmt.getStartingWith(), "starting with");
        }
        return stmt;
    }

    /** name = "value" [, name = "value"]... */
    private void parseAssignments(java.util.Map<String, String> into, String after) {
        do {
            Token name = consume(TokenType.IDENTIFIER, "Expect a variable name after '" + after + "'.");
            consume(TokenType.ASSIGN, "Expect '=' after " + name.getValue() + ", as in " + name.getValue() + " = \"value\".");
            if (match(TokenType.STRING_LITERAL, TokenType.NUMBER_LITERAL, TokenType.IDENTIFIER)) {
                into.put(name.getValue(), previous().getValue());
            } else {
                throw error(peek(), "Expect a value (in quotes) for " + name.getValue() + ".");
            }
        } while (match(TokenType.COMMA));
    }

    /**
     * {@code rewind to Name [when (condition)] at most N times [carrying a = "x", ...] [side effects: ask first|keep|repeat]
     * [if it still fails { ... }] [if blocked { ... }]}
     */
    private RewindStmt parseRewind() {
        Token keyword = advance();
        consume(TokenType.TO, "Expect 'to' after rewind.");
        Token target = consume(TokenType.IDENTIFIER, "Expect the name of a checkpoint after 'rewind to'.");

        String condition = null;
        if (isWord("when")) {
            advance();
            consume(TokenType.LPAREN, "Write the condition in brackets: rewind to " + target.getValue() + " when (score < 7)");
            StringBuilder b = new StringBuilder();
            while (!check(TokenType.RPAREN) && !isAtEnd()) {
                if (isWord("and") || isWord("or")) {
                    throw error(peek(), "a condition is one comparison, such as (score < 7), or one true/false variable; combine several tests into a variable first");
                }
                b.append(advance().getValue());
            }
            consume(TokenType.RPAREN, "Expect ')' after the rewind condition.");
            condition = b.toString();
        }

        if (!isWord("at")) throw error(peek(), "A rewind needs a limit: add \"at most 2 times\" (or another number).");
        advance();
        expectWord("most", "Write the limit as: at most 2 times");
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a number after 'at most', as in: at most 2 times");
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
            if (effects == null) throw error(side, "Side effects can be: ask first, keep or repeat (not \"" + phrase + "\").");
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
                throw error(ifToken, "After a rewind, write either: if it still fails { ... } or: if blocked { ... }");
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

    /** The next token is the plain word {@code word}, whatever kind of token the lexer made of it ("agent" and "to" are keywords elsewhere). */
    private boolean wordIs(String word) {
        return peek().getType() != TokenType.STRING_LITERAL && peek().getType() != TokenType.EOF && word.equals(peek().getValue());
    }

    private void word(String word, String hint) {
        if (!wordIs(word)) throw error(peek(), hint);
        advance();
    }

    private boolean isDecisionStart() {
        return isWord("decision") && peekAt(1).getType() == TokenType.IDENTIFIER && peekAt(2).getType() == TokenType.LBRACE;
    }

    /** {@code decide Name -> variable} */
    private io.github.llm4j.loom.ast.DecideStmt parseDecide() {
        Token keyword = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect the name of a decision after decide.");
        consume(TokenType.ARROW, "Write: decide " + name.getValue() + " -> verdict (the name the answer is kept under)");
        Token variable = consume(TokenType.IDENTIFIER, "Expect a variable name after '->'.");
        io.github.llm4j.loom.ast.DecideStmt stmt = new io.github.llm4j.loom.ast.DecideStmt(name.getValue(), variable.getValue());
        stmt.setLine(keyword.getLine());
        return stmt;
    }

    private int whole(String what) {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a whole number for " + what + ".");
        double d = Double.parseDouble(n.getValue());
        if (d != Math.rint(d)) throw error(n, what + " must be a whole number, not " + n.getValue());
        return (int) d;
    }

    private double percent(String what) {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a percentage for " + what + ", such as 90%.");
        consume(TokenType.PERCENT, "Write " + what + " as a percentage, such as 90% (not " + n.getValue() + ").");
        return Double.parseDouble(n.getValue());
    }

    private io.github.llm4j.loom.autonomy.Level level(String after) {
        Token t = advance();
        io.github.llm4j.loom.autonomy.Level l = io.github.llm4j.loom.autonomy.Level.of(t.getValue());
        if (l == null || t.getType() == TokenType.STRING_LITERAL) throw error(t, "After \"" + after + "\" write watch, suggest or act (not \"" + t.getValue() + "\").");
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
            b.append(t.getType() == TokenType.STRING_LITERAL ? "\"" + t.getValue() + "\"" : t.getValue());
        }
        return b.toString();
    }

    private io.github.llm4j.loom.ast.DecisionDef parseDecision() {
        Token keyword = advance();
        Token name = consume(TokenType.IDENTIFIER, "Expect a decision name.");
        io.github.llm4j.loom.ast.DecisionDef d = new io.github.llm4j.loom.ast.DecisionDef(name.getValue());
        d.setLine(keyword.getLine());
        consume(TokenType.LBRACE, "Expect '{' before the decision body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token first = peek();
            if (wordIs("proposed")) {
                advance();
                word("by", "Write: proposed by: AgentName");
                consume(TokenType.COLON, "Expect ':' after \"proposed by\".");
                d.setAgent(consume(TokenType.IDENTIFIER, "Expect the name of the agent that proposes.").getValue());
            } else if (wordIs("choices")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"choices\".");
                do d.getChoices().add(consume(TokenType.IDENTIFIER, "Expect a choice name, such as approve.").getValue()); while (match(TokenType.COMMA));
            } else if (wordIs("group")) {
                advance();
                word("cases", "Write: group cases by: variable");
                word("by", "Write: group cases by: variable");
                consume(TokenType.COLON, "Expect ':' after \"group cases by\".");
                d.setGroupBy(consume(TokenType.IDENTIFIER, "Expect a variable name after \"group cases by:\".").getValue());
            } else if (wordIs("remember")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"remember\".");
                do d.getRemember().add(consume(TokenType.IDENTIFIER, "Expect a variable name to remember.").getValue()); while (match(TokenType.COMMA));
            } else if (wordIs("dangerous")) {
                advance();
                word("mistake", "Write: dangerous mistake: propose approve, person decides reject");
                consume(TokenType.COLON, "Expect ':' after \"dangerous mistake\".");
                word("propose", "Write: dangerous mistake: propose approve, person decides reject");
                String proposed = consume(TokenType.IDENTIFIER, "Expect the choice the agent proposes.").getValue();
                consume(TokenType.COMMA, "Write: dangerous mistake: propose " + proposed + ", person decides reject");
                word("person", "Write: dangerous mistake: propose " + proposed + ", person decides reject");
                word("decides", "Write: dangerous mistake: propose " + proposed + ", person decides reject");
                d.getDangerous().add(new io.github.llm4j.loom.ast.DecisionDef.Mistake(proposed, consume(TokenType.IDENTIFIER, "Expect the choice the person makes.").getValue()));
            } else if (wordIs("ask")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"ask\" (who decides), as in: ask: support-lead");
                d.setAsk(consume(TokenType.IDENTIFIER, "Expect the name of who decides.").getValue());
            } else if (wordIs("keep")) {
                advance();
                word("records", "Write: keep records for: 180 days");
                word("for", "Write: keep records for: 180 days");
                consume(TokenType.COLON, "Expect ':' after \"keep records for\".");
                d.setKeepDays(whole("the days to keep records"));
                days("how long to keep records");
            } else if (wordIs("when")) {
                advance();
                word("the", "Write: when the agent changes: start over | test it on past cases | keep the trust");
                word("agent", "Write: when the agent changes: start over | test it on past cases | keep the trust");
                word("changes", "Write: when the agent changes: start over | test it on past cases | keep the trust");
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
                    throw error(peek(), "After \"when the agent changes:\" write start over, test it on past cases or keep the trust.");
                }
            } else if (wordIs("tell")) {
                advance();
                d.setTellTool(consume(TokenType.IDENTIFIER, "Expect the name of a tool to tell, as in: tell Slack when trust changes").getValue());
                word("when", "Write: tell " + d.getTellTool() + " when trust changes");
                word("trust", "Write: tell " + d.getTellTool() + " when trust changes");
                word("changes", "Write: tell " + d.getTellTool() + " when trust changes");
            } else if (wordIs("task")) {
                advance();
                consume(TokenType.COLON, "Expect ':' after \"task\".");
                d.setTask(consume(TokenType.STRING_LITERAL, "Expect the task text in quotes.").getValue());
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
            } else {
                throw error(first, "Unexpected \"" + first.getValue() + "\" in decision " + d.getName() + ". A decision holds: proposed by, choices, group cases by, remember, "
                        + "dangerous mistake, ask, keep records for, when the agent changes, tell, task, flag cases, and a trust { } block.");
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after the decision body.");
        return d;
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
                if (!wordIs("after")) throw error(peek(), "\"to " + to.word() + "\" needs \"after N cases\", as in: to " + to.word() + ": after 100 cases over 14 days, agreeing at least 90%");
                advance();
                int cases = whole("the number of cases");
                cases();
                int days = 0;
                if (wordIs("over")) {
                    advance();
                    days = whole("the number of days");
                    days("the time the cases must span");
                }
                consume(TokenType.COMMA, "Write: to " + to.word() + ": after " + cases + " cases over 14 days, agreeing at least 90%");
                word("agreeing", "\"to " + to.word() + "\" needs \"agreeing at least P%\"");
                word("at", "Write: agreeing at least 90%");
                word("least", "Write: agreeing at least 90%");
                double agree = percent("how much the agent must agree");
                boolean none = false;
                Double atMost = null;
                if (match(TokenType.COMMA)) {
                    word("with", "Write: with no dangerous mistakes, or: with at most 1% dangerous mistakes");
                    if (wordIs("no")) {
                        advance();
                        none = true;
                    } else {
                        word("at", "Write: with no dangerous mistakes, or: with at most 1% dangerous mistakes");
                        word("most", "Write: with at most 1% dangerous mistakes");
                        atMost = percent("the dangerous mistakes allowed");
                    }
                    word("dangerous", "Write: dangerous mistakes");
                    word("mistakes", "Write: dangerous mistakes");
                }
                if (d.getUpRules().put(to, new io.github.llm4j.loom.ast.DecisionDef.UpRule(to, cases, days, agree, none, atMost, first.getLine())) != null) {
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
                word("person", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("who", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("doesn't", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("see", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("the", "Write: check 5% of cases with a person who doesn't see the proposal");
                word("proposal", "Write: check 5% of cases with a person who doesn't see the proposal");
            } else if (wordIs("always")) {
                advance();
                word("ask", "Write: always ask a person when amount > 200");
                word("a", "Write: always ask a person when amount > 200");
                word("person", "Write: always ask a person when amount > 200");
                if (wordIs("when")) {
                    advance();
                    String condition = restOfLine();
                    if (condition.isBlank()) throw error(first, "Say when: always ask a person when amount > 200");
                    d.getAskWhen().add(condition);
                } else if (wordIs("after")) {
                    advance();
                    d.setAskAfterPerDay(whole("the cases a day"));
                    cases();
                    word("a", "Write: always ask a person after 50 cases a day");
                    word("day", "Write: always ask a person after 50 cases a day");
                } else {
                    throw error(peek(), "Write: always ask a person when amount > 200, or: always ask a person after 50 cases a day");
                }
            } else if (wordIs("drop")) {
                advance();
                if (!check(TokenType.TO)) throw error(peek(), "Write: drop to suggest when 2 dangerous mistakes in 50 cases");
                advance();
                io.github.llm4j.loom.autonomy.Level to = level("drop to");
                word("when", "Write: drop to " + to.word() + " when 2 dangerous mistakes in 50 cases");
                io.github.llm4j.loom.ast.DecisionDef.DropRule rule;
                if (wordIs("agreement")) {
                    advance();
                    word("falls", "Write: drop to " + to.word() + " when agreement falls below 92%");
                    word("below", "Write: drop to " + to.word() + " when agreement falls below 92%");
                    rule = new io.github.llm4j.loom.ast.DecisionDef.DropRule(to, io.github.llm4j.loom.ast.DecisionDef.Count.AGREEMENT_BELOW, 0, 0, percent("the floor"), first.getLine());
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
                        throw error(peek(), "After a number write dangerous mistakes, reversals or unusable proposals, as in: drop to suggest when 2 reversals in 100 cases");
                    }
                    word("in", "Write: " + n + " ... in 50 cases");
                    int in = whole("the number of cases to look at");
                    cases();
                    rule = new io.github.llm4j.loom.ast.DecisionDef.DropRule(to, count, n, in, 0, first.getLine());
                }
                d.getDropRules().add(rule);
            } else if (wordIs("moving")) {
                advance();
                word("up", "Write: moving up needs approval from: someone, or: moving up is automatic");
                if (wordIs("needs")) {
                    advance();
                    word("approval", "Write: moving up needs approval from: risk-owner");
                    word("from", "Write: moving up needs approval from: risk-owner");
                    consume(TokenType.COLON, "Expect ':' after \"approval from\".");
                    d.setApprover(consume(TokenType.IDENTIFIER, "Expect the name of who approves.").getValue());
                } else if (wordIs("is")) {
                    advance();
                    word("automatic", "Write: moving up is automatic");
                    d.setAutomatic(true);
                } else {
                    throw error(peek(), "Write: moving up needs approval from: risk-owner, or: moving up is automatic");
                }
            } else {
                throw error(first, "Unexpected \"" + first.getValue() + "\" in trust. Write one of: start at, never go above, to <level>:, judge on the latest, check N% of cases, "
                        + "always ask a person, drop to <level> when, moving up.");
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after the trust block.");
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
        
        return new HandoffStmt(payload, target.getValue());
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

        consume(TokenType.ARROW, "Expect '->' to assign delegate result.");
        String varName = nameOrReference("Expect variable name for result (or {item.field}).");

        DelegateStmt stmt = new DelegateStmt(payload, target, varName);

        // Optional per-step schema: the same agent can return different structures in different steps.
        if (check(TokenType.IDENTIFIER) && "expecting".equals(peek().getValue())) {
            advance();
            stmt.setExpecting(parseSchema());
        }

        // Optional, in any order: retry 3 · backoff 2s · timeout 90s · budget 5000 tokens
        while (true) {
            if (match(TokenType.RETRY)) {
                Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
                stmt.setRetryCount((int) Double.parseDouble(count.getValue()));
            } else if (check(TokenType.IDENTIFIER) && ("backoff".equals(peek().getValue()) || "timeout".equals(peek().getValue()))) {
                String option = advance().getValue();
                long millis = durationMillis();
                if (option.equals("backoff")) stmt.setBackoffMillis(millis); else stmt.setTimeoutMillis(millis);
            } else if (isBudgetModifier()) {
                stmt.setBudget(parseBudgetModifier(stmt.getBudget()));
            } else {
                break;
            }
        }

        if (match(TokenType.ON_FAILURE)) {
            consume(TokenType.LBRACE, "Expect '{' before on_failure body.");
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                stmt.getOnFailure().add(parseStatement());
            }
            consume(TokenType.RBRACE, "Expect '}' after on_failure body.");
        }

        return stmt;
    }

    /** An identifier, or a {@code {item.field}} reference resolved at run time. */
    private String nameOrReference(String error) {
        if (match(TokenType.LBRACE)) {
            Token ref = consume(TokenType.IDENTIFIER, error);
            consume(TokenType.RBRACE, "Expect '}' after reference.");
            return "{" + ref.getValue() + "}";
        }
        return consume(TokenType.IDENTIFIER, error).getValue();
    }

    /** A duration such as {@code 2s}, {@code 500ms}, {@code 3m} (a bare number means seconds). */
    private long durationMillis() {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a duration, e.g. 2s or 500ms");
        double value = Double.parseDouble(n.getValue());
        String unit = "s";
        if (check(TokenType.IDENTIFIER) && List.of("ms", "s", "m", "h").contains(peek().getValue())) unit = advance().getValue();
        return (long) switch (unit) {
            case "ms" -> value;
            case "m" -> value * 60_000;
            case "h" -> value * 3_600_000;
            default -> value * 1_000;
        };
    }

    /** for each item in list.path { ... } */
    private ForEachStmt parseForEach(boolean parallel) {
        Token each = consume(TokenType.IDENTIFIER, "Expect 'each' after 'for'.");
        if (!"each".equals(each.getValue())) throw error(each, "Expect 'for each <item> in <list>'.");
        Token item = consume(TokenType.IDENTIFIER, "Expect a name for the item.");
        Token in = consume(TokenType.IDENTIFIER, "Expect 'in' after the item name.");
        if (!"in".equals(in.getValue())) throw error(in, "Expect 'for each <item> in <list>'.");
        Token list = consume(TokenType.IDENTIFIER, "Expect the list to iterate, e.g. review.fixes.");
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
        if (check(TokenType.IDENTIFIER) && "on_exhausted".equals(peek().getValue())) {
            Token keyword = advance();
            if (budget == null) throw error(keyword, "on_exhausted on a for each needs a budget (add 'budget N tokens')");
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
                    // Any word is a valid field name, including ones Loom uses as keywords (note, model, …).
                    Token fieldName = advance();
                    if (fieldName.getValue() == null || !fieldName.getValue().matches("[A-Za-z_][A-Za-z0-9_]*")) {
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
                values.add(consume(TokenType.STRING_LITERAL, "Expect string literal in enum.").getValue());
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
        // We will just read the tokens as a raw conditions string for simplicity right now
        StringBuilder conditionBuilder = new StringBuilder();
        while (!check(TokenType.RPAREN) && !isAtEnd()) {
            conditionBuilder.append(advance().getValue());
        }
        consume(TokenType.RPAREN, "Expect ')' after alt condition.");

        AltStmt altStmt = new AltStmt(conditionBuilder.toString());

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

        consume(TokenType.ARROW, "Expect '->' to assign broadcast result.");
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name for result.");

        BroadcastStmt broadcast = new BroadcastStmt(payload, targetAgents, varName.getValue());
        while (isBudgetModifier()) broadcast.setBudget(parseBudgetModifier(broadcast.getBudget()));
        return broadcast;
    }

    private LoopStmt parseLoopStmt() {
        consume(TokenType.UNTIL, "Expect 'until' after loop.");
        consume(TokenType.LPAREN, "Expect '(' before loop condition.");
        StringBuilder conditionBuilder = new StringBuilder();
        while (!check(TokenType.RPAREN) && !isAtEnd()) {
            conditionBuilder.append(advance().getValue());
        }
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
                throw error(keyword, "on_exhausted needs a bounded loop (add 'max N' or 'budget N tokens')");
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
        Token msg = consume(TokenType.STRING_LITERAL, "Expect string literal message for human prompt.");
        consume(TokenType.ARROW, "Expect '->' to assign human prompt result.");
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name for result.");
        return new HumanPromptStmt(msg.getValue(), varName.getValue());
    }

    // -----------------------------------------------------------------------
    // Tier-1 extension parsers
    // -----------------------------------------------------------------------

    /**
     * Parses a top-level MCP server declaration.
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
                Token val = consume(TokenType.STRING_LITERAL, "Expect string literal for transport.");
                mcp.setTransport(val.getValue());
            } else if (match(TokenType.CMD)) {
                consume(TokenType.COLON, "Expect ':' after cmd.");
                Token val = consume(TokenType.STRING_LITERAL, "Expect string literal for cmd.");
                mcp.setCmd(val.getValue());
            } else {
                throw error(peek(), "Unexpected token in mcp body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after mcp server body.");
        return mcp;
    }

    /**
     * Parses a top-level audit configuration block.
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
                Token val = consume(TokenType.STRING_LITERAL, "Expect string literal for logger type.");
                cfg.setLogger(val.getValue());
            } else if (match(TokenType.PATH)) {
                consume(TokenType.COLON, "Expect ':' after path.");
                Token val = consume(TokenType.STRING_LITERAL, "Expect string literal for audit path.");
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
                kb.setChunkSize(Integer.parseInt(consume(TokenType.NUMBER_LITERAL, "Expect chunk size number.").getValue()));
            } else if (match(TokenType.EMBEDDING)) {
                consume(TokenType.COLON, "Expect ':' after embedding.");
                kb.setEmbeddingProvider(consume(TokenType.STRING_LITERAL, "Expect embedding provider string.").getValue());
            } else if (check(TokenType.IDENTIFIER) && "source".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after source.");
                kb.setPath(consume(TokenType.STRING_LITERAL, "Expect a file or directory, e.g. source: \"docs/\"").getValue());
            } else if (check(TokenType.IDENTIFIER) && ("overlap".equals(peek().getValue()) || "top_k".equals(peek().getValue()))) {
                Token key = advance();
                consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
                Token n = consume(TokenType.NUMBER_LITERAL, "Expect a whole number for " + key.getValue() + ".");
                double v = Double.parseDouble(n.getValue());
                boolean overlap = key.getValue().equals("overlap");
                if (v != Math.floor(v) || v < (overlap ? 0 : 1)) {
                    throw error(n, key.getValue() + " must be a " + (overlap ? "non-negative" : "positive") + " whole number");
                }
                if (overlap) kb.setOverlap((int) v); else kb.setTopK((int) v);
            } else if (check(TokenType.IDENTIFIER) && "store".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after store.");
                if (match(TokenType.MEMORY)) kb.setStore(null);
                else kb.setStore(consume(TokenType.STRING_LITERAL, "Expect an index file path, or memory").getValue());
            } else if (check(TokenType.IDENTIFIER) && "mode".equals(peek().getValue())) {
                advance();
                consume(TokenType.COLON, "Expect ':' after mode.");
                Token m = word("Expect context or tool.");
                switch (m.getValue()) {
                    case "context" -> kb.setMode(io.github.llm4j.loom.ast.KnowledgeDef.Mode.CONTEXT);
                    case "tool" -> kb.setMode(io.github.llm4j.loom.ast.KnowledgeDef.Mode.TOOL);
                    default -> throw error(m, "mode must be context or tool, got '" + m.getValue() + "'");
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
                rp.setStrategy(check(TokenType.STRING_LITERAL) ? advance().getValue()
                        : word("Expect a strategy: cost_aware or fallback").getValue());
            } else if (match(TokenType.PRIMARY)) {
                consume(TokenType.COLON, "Expect ':' after primary.");
                rp.setPrimaryModel(consume(TokenType.STRING_LITERAL, "Expect primary model string.").getValue());
            } else if (match(TokenType.FALLBACK) || (check(TokenType.IDENTIFIER) && "fallbacks".equals(peek().getValue()) && advance() != null)) {
                consume(TokenType.COLON, "Expect ':' after fallback.");
                consume(TokenType.LBRACKET, "Expect '['.");
                if (!check(TokenType.RBRACKET)) {
                    do {
                        rp.addFallbackModel(consume(TokenType.STRING_LITERAL, "Expect model string.").getValue());
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
    private <T extends Settings> T parseSettings(T settings, String what, int line) {
        settings.setLine(line);
        consume(TokenType.LBRACE, "Expect '{' after " + what + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a " + what + " setting, e.g. key: value");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (settings.has(key.getValue())) throw error(key, key.getValue() + " is given twice in " + what);
            settings.put(key.getValue(), optionValue(), key.getLine());
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after " + what + " settings.");
        return settings;
    }

    /** {@code voice {} / guard {}}: a contextual keyword followed by a block (with an optional colon). */
    private boolean isSettingsBlock(String name) {
        if (!check(TokenType.IDENTIFIER) || !name.equals(peek().getValue()) || tokens.size() <= current + 1) return false;
        TokenType next = tokens.get(current + 1).getType();
        return next == TokenType.LBRACE
                || next == TokenType.COLON && tokens.size() > current + 2 && tokens.get(current + 2).getType() == TokenType.LBRACE;
    }

    /** A contextual keyword followed by a name and a block: {@code provider Box { … }}. */
    private boolean isNamedBlock(String name) {
        return check(TokenType.IDENTIFIER) && name.equals(peek().getValue()) && tokens.size() > current + 2
                && tokens.get(current + 1).getType() == TokenType.IDENTIFIER
                && tokens.get(current + 2).getType() == TokenType.LBRACE;
    }

    private ProviderDef parseProviderDef() {
        Token name = consume(TokenType.IDENTIFIER, "Expect a provider name, e.g. provider Box { use: ollama }");
        ProviderDef provider = new ProviderDef(name.getValue());
        provider.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after provider " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a provider option, e.g. use: ollama");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (key.getValue().equals("use")) {
                provider.setKind(word("Expect a provider kind after use:, e.g. use: ollama").getValue());
            } else {
                if (provider.getOptions().containsKey(key.getValue())) throw error(key, "option " + key.getValue() + " is given twice");
                provider.getOptions().put(key.getValue(), optionValue());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after provider " + name.getValue() + ".");
        if (provider.getKind() == null) throw error(name, "provider " + name.getValue() + " needs use: gemini | anthropic | ollama | sarvam");
        return provider;
    }

    private PersonaDef parsePersonaDef() {
        Token name = consume(TokenType.IDENTIFIER, "Expect a persona name, e.g. persona Mentor { role: \"…\" }");
        PersonaDef persona = new PersonaDef(name.getValue());
        persona.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after persona " + name.getValue() + ".");
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token key = word("Expect a persona field: role, expertise, tone, description or constraints");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (!seen.add(key.getValue())) throw error(key, key.getValue() + " is given twice in persona " + name.getValue());
            switch (key.getValue()) {
                case "role" -> persona.setRole(personaText(key));
                case "expertise" -> persona.setExpertise(personaText(key));
                case "tone" -> persona.setTone(personaText(key));
                case "description" -> persona.setDescription(personaText(key));
                case "constraints" -> {
                    consume(TokenType.LBRACKET, "Expect [\"…\", …] after constraints:");
                    if (!check(TokenType.RBRACKET)) {
                        do {
                            persona.getConstraints().add(consume(TokenType.STRING_LITERAL, "Expect a constraint (string).").getValue());
                        } while (match(TokenType.COMMA));
                    }
                    consume(TokenType.RBRACKET, "Expect ']' after constraints.");
                }
                default -> throw error(key, "unknown persona field " + key.getValue()
                        + "; use role, expertise, tone, description or constraints");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after persona " + name.getValue() + ".");
        if (persona.getRole() == null) throw error(name, "persona " + name.getValue() + " needs role: \"…\"");
        return persona;
    }

    private String personaText(Token key) {
        return consume(TokenType.STRING_LITERAL, "Expect a string after " + key.getValue() + ":").getValue();
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
                sd.setPattern(consume(TokenType.STRING_LITERAL, "Expect pattern string.").getValue());
            } else if (match(TokenType.AGENT)) {
                consume(TokenType.COLON, "Expect ':' after agent.");
                sd.setAgentName(consume(TokenType.IDENTIFIER, "Expect agent name.").getValue());
            } else if (payloadMatch()) { // Generic lookup for task
                advance(); // consume 'task'
                consume(TokenType.COLON, "Expect ':' after key.");
                sd.setTask(consume(TokenType.STRING_LITERAL, "Expect task string.").getValue());
            } else if (match(TokenType.PATH)) { // Reuse path for delay? or just identifier
                 consume(TokenType.COLON, "Expect ':'.");
                 sd.setInitialDelay(consume(TokenType.STRING_LITERAL, "Expect delay string.").getValue());
            } else if (peek().getType() == TokenType.IDENTIFIER) {
                Token keyToken = advance();
                String key = keyToken.getValue();
                consume(TokenType.COLON, "Expect ':' after key.");
                switch (key) {
                    case "cron" -> {
                        Token v = consume(TokenType.STRING_LITERAL, "Expect a cron string, e.g. cron: \"0 7 * * *\"");
                        try {
                            io.github.llm4j.loom.trigger.CronSchedule.parse(v.getValue());
                        } catch (IllegalArgumentException e) {
                            throw error(v, e.getMessage());
                        }
                        sd.setCron(v.getValue());
                    }
                    case "every" -> sd.setEvery(duration(keyToken));
                    case "timezone" -> {
                        Token v = consume(TokenType.STRING_LITERAL, "Expect a time zone string, e.g. timezone: \"Asia/Kolkata\"");
                        try {
                            java.time.ZoneId.of(v.getValue());
                        } catch (java.time.DateTimeException e) {
                            throw error(v, "Unknown time zone '" + v.getValue() + "'");
                        }
                        sd.setTimezone(v.getValue());
                    }
                    case "run" -> {
                        sd.setRunWorkflow(consume(TokenType.IDENTIFIER, "Expect a workflow to run, e.g. run: Digest()").getValue());
                        consume(TokenType.LPAREN, "Expect '(' after the workflow name.");
                        if (!check(TokenType.RPAREN)) {
                            do {
                                Token argName = consume(TokenType.IDENTIFIER, "Expect argument name.");
                                consume(TokenType.ASSIGN, "Expect '=' after argument name.");
                                sd.getRunArgs().put(argName.getValue(),
                                        consume(TokenType.STRING_LITERAL, "Expect a string argument value.").getValue());
                            } while (match(TokenType.COMMA));
                        }
                        consume(TokenType.RPAREN, "Expect ')' after run arguments.");
                    }
                    case "misfire" -> {
                        Token v = consume(TokenType.IDENTIFIER, "Expect run_once or skip after misfire.");
                        if (!v.getValue().equals("run_once") && !v.getValue().equals("skip")) {
                            throw error(v, "misfire must be run_once or skip, got '" + v.getValue() + "'");
                        }
                        sd.setMisfire(v.getValue());
                    }
                    case "overlap" -> {
                        Token v = consume(TokenType.IDENTIFIER, "Expect skip or queue after overlap.");
                        if (!v.getValue().equals("skip") && !v.getValue().equals("queue")) {
                            throw error(v, "overlap must be skip or queue, got '" + v.getValue() + "'");
                        }
                        sd.setOverlap(v.getValue());
                    }
                    case "task" -> sd.setTask(consume(TokenType.STRING_LITERAL, "Expect string value.").getValue());
                    case "initial_delay" -> sd.setInitialDelay(consume(TokenType.STRING_LITERAL, "Expect string value.").getValue());
                    default -> throw error(keyToken, "Unknown schedule field '" + key
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
            throw error(nameToken, "schedule " + sd.getName() + ": use run (a workflow) or agent + task, not both");
        }
        if (sd.getRunWorkflow() == null && sd.getAgentName() == null) {
            throw error(close, "schedule " + sd.getName() + " needs run: <Workflow>(...) or agent + task");
        }
        if (sd.getCron() == null && sd.getEvery() == null && sd.getPattern() == null && sd.getRunWorkflow() != null) {
            throw error(close, "schedule " + sd.getName() + " needs cron: \"...\" or every: <duration>");
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

    /** {@code tool Name { use: kind  option: value … }} — after the keyword. */
    private io.github.llm4j.loom.ast.ToolDef parseToolDef() {
        Token name = consume(TokenType.IDENTIFIER, "Expect a tool name, e.g. tool Search { use: duckduckgo }");
        io.github.llm4j.loom.ast.ToolDef tool = new io.github.llm4j.loom.ast.ToolDef(name.getValue());
        tool.setLine(name.getLine());
        consume(TokenType.LBRACE, "Expect '{' after tool " + name.getValue() + ".");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            // A quoted key lets option names hold characters a bare word can't: "header.X-Trace-Id": "abc"
            Token key = check(TokenType.STRING_LITERAL) ? advance() : word("Expect a tool option, e.g. use: serpapi");
            consume(TokenType.COLON, "Expect ':' after " + key.getValue() + ".");
            if (key.getValue().equals("use")) {
                tool.setKind(word("Expect a tool kind after use:, e.g. use: duckduckgo").getValue());
            } else {
                if (tool.getOptions().containsKey(key.getValue())) throw error(key, "option " + key.getValue() + " is given twice");
                tool.getOptions().put(key.getValue(), optionValue());
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after tool " + name.getValue() + ".");
        if (tool.getKind() == null) throw error(name, "tool " + name.getValue() + " needs use: <kind>");
        return tool;
    }

    /** A bare word: an identifier or a keyword (option names like path, type, class). */
    private Token word(String message) {
        if (!isAtEnd() && peek().getValue() != null && peek().getValue().matches("[A-Za-z_][A-Za-z0-9_.]*")
                && peek().getType() != TokenType.STRING_LITERAL) {
            return advance();
        }
        throw error(peek(), message);
    }

    /** A string, number, true/false, a word, or {@code env.NAME}. */
    private io.github.llm4j.loom.ast.ToolDef.OptionValue optionValue() {
        if (check(TokenType.NUMBER_LITERAL)) {
            Token number = advance();
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(number.getValue() + unitSuffix(number));
        }
        if (check(TokenType.STRING_LITERAL)) {
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(advance().getValue());
        }
        Token w = word("Expect a value: a \"string\", a number, true/false, env.NAME or secret.NAME");
        if (w.getValue().startsWith("env.")) {
            String var = w.getValue().substring(4);
            if (var.isEmpty() || var.contains(".")) throw error(w, "an environment reference is env.NAME, got " + w.getValue());
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.env(var);
        }
        if (w.getValue().startsWith("secret.")) {
            String name = w.getValue().substring(7);
            if (!io.github.llm4j.secret.SecretNames.isValid(name)) {
                throw error(w, "a secret reference is secret.NAME (a letter, then letters, digits, _ or -), got " + w.getValue());
            }
            return io.github.llm4j.loom.ast.ToolDef.OptionValue.secret(name);
        }
        return io.github.llm4j.loom.ast.ToolDef.OptionValue.literal(w.getValue());
    }

    private static final java.util.Set<String> UNITS = java.util.Set.of("ms", "s", "m", "h", "k", "kb", "mb");

    /**
     * A unit written straight after a number ({@code 20s}, {@code 64k}) belongs to it. A word that is itself
     * a key (followed by ':') is left alone.
     */
    private String unitSuffix(Token number) {
        if (isAtEnd()) return "";
        Token next = peek();
        boolean adjacent = next.getType() == TokenType.IDENTIFIER && next.getLine() == number.getLine()
                && next.getColumn() == number.getColumn() + number.getValue().length();
        boolean isKey = tokens.size() > current + 1 && tokens.get(current + 1).getType() == TokenType.COLON;
        if (adjacent && !isKey && UNITS.contains(next.getValue().toLowerCase(java.util.Locale.ROOT))) return advance().getValue();
        return "";
    }

    // ── Budgets ──────────────────────────────────────────────────────────────────────────────

    private boolean isBudgetKeyword() {
        return check(TokenType.IDENTIFIER) && "budget".equals(peek().getValue())
                && tokens.size() > current + 1 && tokens.get(current + 1).getType() == TokenType.LBRACE;
    }

    /** {@code budget} followed by a number or a string: a statement's budget modifier. */
    private boolean isBudgetModifier() {
        if (!check(TokenType.IDENTIFIER) || !"budget".equals(peek().getValue()) || tokens.size() <= current + 1) return false;
        TokenType next = tokens.get(current + 1).getType();
        return next == TokenType.NUMBER_LITERAL || next == TokenType.STRING_LITERAL;
    }

    /** A contextual keyword opening a block: {@code name {}. */
    private boolean isBlockKeyword(String name) {
        return check(TokenType.IDENTIFIER) && name.equals(peek().getValue())
                && tokens.size() > current + 1 && tokens.get(current + 1).getType() == TokenType.LBRACE;
    }

    /** {@code rate_limits { on_limit: suspend|wait|fail  max_wait: 24h  max_resumes: 50 }} — after the keyword. */
    private io.github.llm4j.loom.ast.RateLimitDef parseRateLimits() {
        io.github.llm4j.loom.ast.RateLimitDef def = new io.github.llm4j.loom.ast.RateLimitDef();
        consume(TokenType.LBRACE, "Expect '{' after rate_limits.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token field = consume(TokenType.IDENTIFIER, "Expect a rate_limits field: on_limit, max_wait or max_resumes.");
            consume(TokenType.COLON, "Expect ':' after '" + field.getValue() + "'.");
            switch (field.getValue()) {
                case "on_limit" -> {
                    Token v = consume(TokenType.IDENTIFIER, "Expect suspend, wait or fail after on_limit.");
                    try {
                        def.setOnLimit(io.github.llm4j.loom.ast.RateLimitDef.OnLimit.valueOf(v.getValue().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw error(v, "on_limit must be suspend, wait or fail, got '" + v.getValue() + "'");
                    }
                }
                case "max_wait" -> def.setMaxWait(duration(field));
                case "max_resumes" -> def.setMaxResumes((int) Math.min(Integer.MAX_VALUE, positiveWhole(field)));
                default -> throw error(field, "Unknown rate_limits field '" + field.getValue()
                        + "'. Use on_limit, max_wait or max_resumes.");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after rate_limits block.");
        return def;
    }

    /** A positive duration: {@code 30s}, {@code 15m}, {@code 6h}, {@code 2d} (or the same as a string). */
    private java.time.Duration duration(Token field) {
        Token n;
        String unit;
        if (check(TokenType.STRING_LITERAL)) {
            n = advance();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*(\\d+)\\s*([smhd])\\s*").matcher(n.getValue());
            if (!m.matches()) throw error(n, field.getValue() + " must be a duration like 30s, 15m, 6h or 2d, got \"" + n.getValue() + "\"");
            return durationOf(n, field, Long.parseLong(m.group(1)), m.group(2));
        }
        n = consume(TokenType.NUMBER_LITERAL, "Expect a duration for " + field.getValue() + ", e.g. 30s, 15m, 6h or 2d.");
        Token u = consume(TokenType.IDENTIFIER, "Expect a unit (s, m, h or d) after " + n.getValue() + ", e.g. " + n.getValue() + "h.");
        unit = u.getValue();
        double value = Double.parseDouble(n.getValue());
        if (value != Math.floor(value)) throw error(n, field.getValue() + " must be a whole number of " + unit);
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
            throw error(w, "A budget window must be per minute, per hour or per day, got 'per " + w.getValue() + "'");
        }
        if (budget.getWindow() != null && budget.getWindow() != window) {
            throw error(w, "A budget has one window: it is already per " + budget.getWindow().name().toLowerCase(java.util.Locale.ROOT));
        }
        budget.setWindow(window);
    }

    /** {@code budget { tokens: N [per day]  calls: N  cost: "$X"  warn_at: 80%  per_call: N  when_exhausted: stop }} — after the keyword. */
    private io.github.llm4j.loom.ast.BudgetDef parseBudgetBlock(boolean forAgent) {
        io.github.llm4j.loom.ast.BudgetDef budget = new io.github.llm4j.loom.ast.BudgetDef();
        consume(TokenType.LBRACE, "Expect '{' after budget.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            Token field = consume(TokenType.IDENTIFIER, "Expect a budget field: tokens, calls, cost, warn_at, when_exhausted"
                    + (forAgent ? " or per_call" : "") + ".");
            consume(TokenType.COLON, "Expect ':' after budget field '" + field.getValue() + "'.");
            switch (field.getValue()) {
                case "tokens" -> { budget.setTokens(positiveWhole(field)); window(budget); }
                case "calls" -> { budget.setCalls(positiveWhole(field)); window(budget); }
                case "cost" -> { budget.setCost(money(field)); window(budget); }
                case "when_exhausted" -> {
                    Token v = consume(TokenType.IDENTIFIER, "Expect stop, suspend or ask after when_exhausted.");
                    try {
                        budget.setWhenExhausted(io.github.llm4j.loom.ast.BudgetDef.WhenExhausted.valueOf(
                                v.getValue().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw error(v, "when_exhausted must be stop, suspend or ask, got '" + v.getValue() + "'");
                    }
                }
                case "per_call" -> {
                    if (!forAgent) throw error(field, "per_call is only allowed in an agent's budget block.");
                    budget.setPerCall((int) Math.min(Integer.MAX_VALUE, positiveWhole(field)));
                }
                case "warn_at" -> {
                    Token n = consume(TokenType.NUMBER_LITERAL, "Expect a percentage for warn_at, e.g. warn_at: 80%");
                    double value = Double.parseDouble(n.getValue());
                    if (match(TokenType.PERCENT) || value > 1) value = value / 100.0;
                    if (value <= 0 || value > 1) throw error(n, "warn_at must be between 1% and 100%, got " + n.getValue());
                    budget.setWarnAt(value);
                }
                default -> throw error(field, "Unknown budget field '" + field.getValue()
                        + "'. Use tokens, calls, cost, warn_at, when_exhausted" + (forAgent ? " or per_call" : "") + ".");
            }
            match(TokenType.COMMA);
        }
        consume(TokenType.RBRACE, "Expect '}' after budget block.");
        if (budget.getWhenExhausted() == io.github.llm4j.loom.ast.BudgetDef.WhenExhausted.SUSPEND && budget.getWindow() == null) {
            throw error(previous(), "when_exhausted: suspend needs a budget that refills, e.g. tokens: 100000 per day");
        }
        return budget;
    }

    /** {@code budget 5000 tokens}, {@code budget 10 calls} or {@code budget "$0.05"}, merged into {@code into}. */
    private io.github.llm4j.loom.ast.BudgetDef parseBudgetModifier(io.github.llm4j.loom.ast.BudgetDef into) {
        Token keyword = advance(); // budget
        io.github.llm4j.loom.ast.BudgetDef budget = into != null ? into : new io.github.llm4j.loom.ast.BudgetDef();
        if (check(TokenType.STRING_LITERAL)) {
            budget.setCost(money(keyword));
            return budget;
        }
        long n = positiveWhole(keyword);
        Token unit = consume(TokenType.IDENTIFIER, "Expect 'tokens' or 'calls' after the budget amount, e.g. budget 5000 tokens");
        switch (unit.getValue()) {
            case "tokens" -> budget.setTokens(n);
            case "calls" -> budget.setCalls(n);
            default -> throw error(unit, "Expect 'tokens' or 'calls' after the budget amount, got '" + unit.getValue() + "'");
        }
        return budget;
    }

    private long positiveWhole(Token field) {
        Token n = consume(TokenType.NUMBER_LITERAL, "Expect a whole number for budget " + field.getValue() + ".");
        double value = Double.parseDouble(n.getValue());
        if (value <= 0 || value != Math.floor(value)) {
            throw error(n, "budget " + field.getValue() + " must be a positive whole number, got " + n.getValue());
        }
        return (long) value;
    }

    /** A cost written with its currency symbol, e.g. "$0.50". */
    private java.math.BigDecimal money(Token field) {
        Token s = consume(TokenType.STRING_LITERAL, "Expect a cost with its currency symbol, e.g. \"$0.50\".");
        String raw = s.getValue().strip();
        if (!raw.matches("[$€£₹¥]\\s*\\d+(\\.\\d+)?")) {
            throw error(s, "budget cost must include a currency symbol, e.g. \"$0.50\", got \"" + raw + "\"");
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(raw.substring(1).strip());
        if (amount.signum() <= 0) throw error(s, "budget cost must be positive, got \"" + raw + "\"");
        return amount;
    }

    private RuntimeException error(Token token, String message) {
        return new RuntimeException("Parse error at line " + token.getLine() + " col " + token.getColumn() + " (" + token.getType() + "): " + message);
    }
}
