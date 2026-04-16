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
            } else if (match(TokenType.WORKFLOW)) {
                script.addWorkflow(parseWorkflow());
            } else {
                throw error(peek(), "Expected 'agent' or 'workflow' declaration, but got: " + peek().getType());
            }
        }

        return script;
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

        return new DelegateStmt(payload, target.getValue(), varName.getValue());
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
