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
            } else {
                throw error(peek(), "Expected 'agent', 'workflow', 'mcp', 'audit', 'knowledge', 'routing', or 'schedule' declaration, but got: " + peek().getType());
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
                Token personaToken = consume(TokenType.STRING_LITERAL, "Expect string literal for persona name.");
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
                consume(TokenType.COLON, "Expect ':' after memory.");
                agent.setMemory(parseMemoryConfig());
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
            return parseParallelStatement();
        } else if (match(TokenType.OBSERVE)) {
            return parseObserveStatement();
        } else if (match(TokenType.CALL)) {
            return parseCallStmt();
        }
        
        throw error(peek(), "Expected statement, got " + peek().getType());
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
        Token target = consume(TokenType.IDENTIFIER, "Expect target agent identifier.");

        consume(TokenType.ARROW, "Expect '->' to assign delegate result.");
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name for result.");

        DelegateStmt stmt = new DelegateStmt(payload, target.getValue(), varName.getValue());

        if (match(TokenType.RETRY)) {
            Token count = consume(TokenType.NUMBER_LITERAL, "Expect number of retries.");
            stmt.setRetryCount((int) Double.parseDouble(count.getValue()));
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
            java.util.Map<String, SchemaDef> fields = new java.util.HashMap<>();
            if (!check(TokenType.RBRACE)) {
                do {
                    Token fieldName = consume(TokenType.IDENTIFIER, "Expect field name.");
                    consume(TokenType.COLON, "Expect ':' after field name.");
                    fields.put(fieldName.getValue(), parseSchema());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RBRACE, "Expect '}' after object schema.");
            schema.setFields(fields);
            return schema;
        } else if (match(TokenType.LIST)) {
            consume(TokenType.LT, "Expect '<' after list.");
            SchemaDef schema = new SchemaDef(SchemaDef.Type.LIST);
            schema.setElementType(parseSchema());
            consume(TokenType.GT, "Expect '>' after list type.");
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

        return new BroadcastStmt(payload, targetAgents, varName.getValue());
    }

    private LoopStmt parseLoopStmt() {
        consume(TokenType.UNTIL, "Expect 'until' after loop.");
        consume(TokenType.LPAREN, "Expect '(' before loop condition.");
        StringBuilder conditionBuilder = new StringBuilder();
        while (!check(TokenType.RPAREN) && !isAtEnd()) {
            conditionBuilder.append(advance().getValue());
        }
        consume(TokenType.RPAREN, "Expect ')' after loop condition.");

        consume(TokenType.LBRACE, "Expect '{' before loop body.");
        List<Statement> body = new java.util.ArrayList<>();
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            body.add(parseStatement());
        }
        consume(TokenType.RBRACE, "Expect '}' after loop body.");

        return new LoopStmt(conditionBuilder.toString(), body);
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

        consume(TokenType.LBRACE, "Expect '{' before routing body.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.STRATEGY)) {
                consume(TokenType.COLON, "Expect ':' after strategy.");
                rp.setStrategy(consume(TokenType.STRING_LITERAL, "Expect strategy string.").getValue());
            } else if (match(TokenType.PRIMARY)) {
                consume(TokenType.COLON, "Expect ':' after primary.");
                rp.setPrimaryModel(consume(TokenType.STRING_LITERAL, "Expect primary model string.").getValue());
            } else if (match(TokenType.FALLBACK)) {
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

    private AgentDef.MemoryConfig parseMemoryConfig() {
        AgentDef.MemoryConfig cfg = new AgentDef.MemoryConfig();
        consume(TokenType.LBRACE, "Expect '{' before memory config.");
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.TYPE)) {
                consume(TokenType.COLON, "Expect ':' after type.");
                cfg.setType(consume(TokenType.STRING_LITERAL, "Expect memory type string.").getValue());
            } else if (match(TokenType.PATH)) {
                consume(TokenType.COLON, "Expect ':' after path.");
                cfg.setPath(consume(TokenType.STRING_LITERAL, "Expect path string.").getValue());
            } else if (match(TokenType.NUMBER_LITERAL)) { // Assume this was mean to be a limit? Wait, I need a keyword
                 // Actually I'll use match(TokenType.IDENTIFIER) and check if it's "limit"
            } else if (peek().getType() == TokenType.IDENTIFIER && peek().getValue().equals("limit")) {
                advance();
                consume(TokenType.COLON, "Expect ':'.");
                cfg.setLimit(Integer.parseInt(consume(TokenType.NUMBER_LITERAL, "Expect number.").getValue()));
            } else {
                throw error(peek(), "Unexpected token in memory body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after memory config.");
        return cfg;
    }

    private GuardrailStmt parseGuardrailStatement() {
        consume(TokenType.LPAREN, "Expect '(' after guardrail.");
        Token typeToken = consume(TokenType.IDENTIFIER, "Expect guardrail type (e.g. PII).");
        consume(TokenType.RPAREN, "Expect ')'.");

        GuardrailStmt stmt = new GuardrailStmt(typeToken.getValue());

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
                String key = advance().getValue();
                consume(TokenType.COLON, "Expect ':' after key.");
                String value = consume(TokenType.STRING_LITERAL, "Expect string value.").getValue();
                if ("task".equals(key)) sd.setTask(value);
                else if ("initial_delay".equals(key)) sd.setInitialDelay(value);
            } else {
                throw error(peek(), "Unexpected token in schedule body: " + peek().getType());
            }
        }
        consume(TokenType.RBRACE, "Expect '}' after schedule body.");
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

    private RuntimeException error(Token token, String message) {
        return new RuntimeException("Parse error at line " + token.getLine() + " col " + token.getColumn() + " (" + token.getType() + "): " + message);
    }
}
