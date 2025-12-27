#### File: `.gitignore`
> Build outputs .class

#### File: `README.md`
> Gemini ReAct Java A production-ready Java client for Google Gemini with built-in ReAct Agents and Tooling. Features

#### File: `pom.xml`

#### File: `run_aviation_chatbot.sh`
> !/bin/bash Check for API Keys Build Library

#### File: `test_api_key_fix.sh`
> !/bin/bash

#### File: `test_truncation.sh`
> !/bin/bash

#### File: `aviation-chatbot/pom.xml`

#### File: `aviation-chatbot/src/main/java/io/github/llm4j/aviation/ChatbotCLI.java`
- **Classes**: ChatbotCLI
- **Major Functions**: main
- **Dependencies**: io.github.llm4j.LLMClient, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, io.github.llm4j.agent.AgentResult, io.github.llm4j.agent.ReActAgent, io.github.llm4j.agent.tools.CurrentTimeTool, java.util.Scanner, io.github.llm4j.config.LLMConfig

#### File: `aviation-chatbot/src/main/java/io/github/llm4j/aviation/ChatbotServer.java`
- **Classes**: ChatbotServer
- **Major Functions**: main, handleChat
- **Dependencies**: io.javalin.http.Context, java.util.HashMap, io.github.llm4j.LLMClient, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, io.javalin.Javalin, io.github.llm4j.agent.AgentResult, java.util.Map, io.github.llm4j.agent.ReActAgent, org.slf4j.Logger, org.slf4j.LoggerFactory, io.github.llm4j.config.LLMConfig

#### File: `aviation-chatbot/src/main/resources/aviationstack-openapi.json`

#### File: `aviation-chatbot/src/test/java/io/github/llm4j/aviation/AviationIntegrationTest.java`
- **Classes**: AviationIntegrationTest
- **Major Functions**: setup, testAgentCapabilities
- **Dependencies**: io.github.llm4j.LLMClient, org.junit.jupiter.api.BeforeAll, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, io.github.llm4j.agent.AgentResult, org.junit.jupiter.api.Test, org.junit.jupiter.params.ParameterizedTest, org.junit.jupiter.params.provider.ValueSource, org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable, io.github.llm4j.agent.ReActAgent, io.github.llm4j.agent.tools.CurrentTimeTool, io.github.llm4j.config.LLMConfig

#### File: `aviation-chatbot-ui/.browserslistrc`
> This file is used by the build system to adjust CSS and JS output to support the specified browsers below. For additional information regarding the format and rule options, please see: https://github.

#### File: `aviation-chatbot-ui/.editorconfig`
> Editor configuration, see https://editorconfig.org

#### File: `aviation-chatbot-ui/.gitignore`
> See http://help.github.com/ignore-files/ for more about ignoring files. compiled output Only exists if Bazel was run

#### File: `aviation-chatbot-ui/README.md`
> AviationChatbotUi Development server Code scaffolding

#### File: `aviation-chatbot-ui/angular.json`

#### File: `aviation-chatbot-ui/karma.conf.js`
> Karma configuration file, see link for more information https://karma-runner.github.io/1.0/config/configuration-file.html you can add configuration options for Jasmine here

#### File: `aviation-chatbot-ui/package-lock.json`

#### File: `aviation-chatbot-ui/package.json`

#### File: `aviation-chatbot-ui/tsconfig.app.json`
> To learn more about this file see: https://angular.io/config/tsconfig. 

#### File: `aviation-chatbot-ui/tsconfig.json`
> To learn more about this file see: https://angular.io/config/tsconfig. 

#### File: `aviation-chatbot-ui/tsconfig.spec.json`
> To learn more about this file see: https://angular.io/config/tsconfig. 

#### File: `aviation-chatbot-ui/tslint.json`

#### File: `aviation-chatbot-ui/e2e/protractor.conf.js`
> @ts-check Protractor configuration file, see link for more information https://github.com/angular/protractor/blob/master/lib/config.ts

#### File: `aviation-chatbot-ui/e2e/tsconfig.json`
> To learn more about this file see: https://angular.io/config/tsconfig. 

#### File: `aviation-chatbot-ui/e2e/src/app.e2e-spec.ts`
- **Dependencies**: protractor, ./app.po
> Assert that there are no errors emitted from the browser

#### File: `aviation-chatbot-ui/e2e/src/app.po.ts`
- **Classes**: AppPage
- **Dependencies**: protractor

#### File: `aviation-chatbot-ui/src/favicon.ico`

#### File: `aviation-chatbot-ui/src/index.html`

#### File: `aviation-chatbot-ui/src/main.ts`
- **Major Functions**: if
- **Dependencies**: ./app/app.module, ./environments/environment, @angular/core, @angular/platform-browser-dynamic

#### File: `aviation-chatbot-ui/src/polyfills.ts`
- **Dependencies**: zone.js/dist/zone, ./zone-flags, web-animations-js, classlist.js
> This file includes polyfills needed by Angular and is loaded before the app. You can add your own extra polyfills to this file. This file is divided into 2 sections:

#### File: `aviation-chatbot-ui/src/styles.scss`
> You can add global styles to this file, and also import other style files 

#### File: `aviation-chatbot-ui/src/test.ts`
- **Dependencies**: zone.js/dist/zone-testing, @angular/core/testing
> This file is required by karma.conf.js and loads recursively all the .spec and framework files First, initialize the Angular testing environment.

#### File: `aviation-chatbot-ui/src/app/app.component.html`

#### File: `aviation-chatbot-ui/src/app/app.component.scss`

#### File: `aviation-chatbot-ui/src/app/app.component.ts`
- **Classes**: AppComponent
- **Dependencies**: @angular/core

#### File: `aviation-chatbot-ui/src/app/app.module.ts`
- **Classes**: AppModule
- **Dependencies**: @angular/core, @angular/common/http, @angular/forms, @angular/platform-browser, ./app.component, ./components/message-list/message-list.component, ./components/input-area/input-area.component, ./components/chat-container/chat-container.component, ./components/message-bubble/message-bubble.component

#### File: `aviation-chatbot-ui/src/app/components/chat-container/chat-container.component.html`

#### File: `aviation-chatbot-ui/src/app/components/chat-container/chat-container.component.scss`

#### File: `aviation-chatbot-ui/src/app/components/chat-container/chat-container.component.ts`
- **Classes**: ChatContainerComponent
- **Major Functions**: constructor
- **Dependencies**: @angular/core, ../../models/message.model, ../../services/chat.service
> Add initial welcome message

#### File: `aviation-chatbot-ui/src/app/components/input-area/input-area.component.html`

#### File: `aviation-chatbot-ui/src/app/components/input-area/input-area.component.scss`

#### File: `aviation-chatbot-ui/src/app/components/input-area/input-area.component.ts`
- **Classes**: InputAreaComponent
- **Major Functions**: constructor, if
- **Dependencies**: @angular/core

#### File: `aviation-chatbot-ui/src/app/components/message-bubble/message-bubble.component.html`

#### File: `aviation-chatbot-ui/src/app/components/message-bubble/message-bubble.component.scss`

#### File: `aviation-chatbot-ui/src/app/components/message-bubble/message-bubble.component.ts`
- **Classes**: MessageBubbleComponent
- **Major Functions**: constructor
- **Dependencies**: @angular/core, ../../models/message.model

#### File: `aviation-chatbot-ui/src/app/components/message-list/message-list.component.html`

#### File: `aviation-chatbot-ui/src/app/components/message-list/message-list.component.scss`

#### File: `aviation-chatbot-ui/src/app/components/message-list/message-list.component.ts`
- **Classes**: MessageListComponent
- **Major Functions**: constructor, ngAfterViewChecked, catch
- **Dependencies**: @angular/core, ../../models/message.model

#### File: `aviation-chatbot-ui/src/app/models/message.model.ts`
- **Classes**: Message

#### File: `aviation-chatbot-ui/src/app/services/chat.service.ts`
- **Classes**: ChatResponse, ChatService
- **Major Functions**: constructor
- **Dependencies**: @angular/common/http, rxjs/operators, @angular/core, rxjs

#### File: `aviation-chatbot-ui/src/assets/.gitkeep`

#### File: `aviation-chatbot-ui/src/environments/environment.prod.ts`

#### File: `aviation-chatbot-ui/src/environments/environment.ts`
- **Dependencies**: zone.js/dist/zone-error
> This file can be replaced during build by using the `fileReplacements` array. `ng build --prod` replaces `environment.ts` with `environment.prod.ts`. The list of file replacements can be found in `ang

#### File: `chatbot-app/README.md`
> Simple Chatbot Application Features Prerequisites

#### File: `chatbot-app/pom.xml`

#### File: `chatbot-app/run.sh`
> !/bin/bash Simple Chatbot Launcher Script Check for API keys

#### File: `chatbot-app/src/main/java/io/github/chatbot/ChatbotApp.java`
- **Classes**: ChatbotApp
- **Major Functions**: ChatbotApp, run, if, if, if, if, if, if, if, printWelcome, printHelp, printHistory, printError, handleSaveCommand, handleLoadCommand
- **Dependencies**: io.github.llm4j.exception.*, io.github.llm4j.LLMClient, io.github.llm4j.model.LLMRequest, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, org.fusesource.jansi.AnsiConsole, org.jline.reader.*, java.time.format.DateTimeFormatter, org.jline.terminal.Terminal, java.time.LocalDateTime, org.fusesource.jansi.Ansi, java.io.IOException, org.jline.terminal.TerminalBuilder, io.github.llm4j.config.LLMConfig, io.github.llm4j.model.LLMResponse

#### File: `chatbot-app/src/main/java/io/github/chatbot/ConversationHistory.java`
- **Classes**: ConversationHistory
- **Major Functions**: ConversationHistory, ConversationHistory, addSystemMessage, addUserMessage, addAssistantMessage, addMessage, getMessages, clear, size, trimHistory
- **Dependencies**: java.util.List, io.github.llm4j.model.Message, java.util.ArrayList
> Manages conversation history for the chatbot.

#### File: `chatbot-app/src/main/java/io/github/chatbot/ConversationPersistence.java`
- **Classes**: ConversationPersistence, SavedConversation, LoadedConversation, ConversationInfo
- **Major Functions**: loadConversation, listConversations, deleteConversation, ensureConversationsDirectory, sanitizeFileName, SavedConversation, SavedConversation, LoadedConversation, getHistory, getSystemPrompt, ConversationInfo, getName, getMessageCount, getSavedAt, getFormattedDate
- **Dependencies**: com.fasterxml.jackson.databind.ObjectMapper, java.time.LocalDateTime, com.fasterxml.jackson.annotation.JsonProperty, java.io.File, com.fasterxml.jackson.databind.SerializationFeature, java.time.format.DateTimeFormatter, io.github.llm4j.model.Message, java.util.ArrayList, java.util.stream.Collectors, java.util.stream.Stream, java.util.List, com.fasterxml.jackson.datatype.jsr310.JavaTimeModule, java.nio.file.Files, java.nio.file.Path, java.io.IOException

#### File: `src/main/java/io/github/llm4j/DefaultLLMClient.java`
- **Classes**: DefaultLLMClient
- **Major Functions**: DefaultLLMClient, chat, chatStream, getProviderName
- **Dependencies**: io.github.llm4j.model.LLMRequest, java.util.Objects, java.util.stream.Stream, io.github.llm4j.provider.LLMProvider, io.github.llm4j.model.LLMResponse
> Default implementation of LLMClient that delegates to a configured provider.

#### File: `src/main/java/io/github/llm4j/LLMClient.java`
- **Classes**: for, provide, LLMClient
- **Dependencies**: io.github.llm4j.model.LLMRequest, io.github.llm4j.model.LLMResponse, java.util.stream.Stream
> Main interface for interacting with LLM providers. Implementations of this interface provide a unified API for making requests to different LLM services.

#### File: `src/main/java/io/github/llm4j/agent/AgentResult.java`
- **Classes**: AgentResult, AgentStep, Builder
- **Major Functions**: AgentResult, getFinalAnswer, getSteps, getIterations, isCompleted, builder, toString, AgentStep, getThought, getAction, getActionInput, getObservation, toString, Builder, finalAnswer
- **Dependencies**: java.util.List, java.util.ArrayList, java.util.Collections
> Represents the result of a ReAct agent execution.

#### File: `src/main/java/io/github/llm4j/agent/ReActAgent.java`
- **Classes**: ReActAgent, Builder
- **Major Functions**: ReActAgent, run, extractPattern, buildDefaultSystemPrompt, builder, Builder, llmClient, tools, addTool, systemPrompt, maxIterations, temperature, build
- **Dependencies**: io.github.llm4j.model.LLMRequest, io.github.llm4j.LLMClient, com.fasterxml.jackson.databind.ObjectMapper, java.util.regex.Pattern, java.util.regex.Matcher, java.util.*, com.fasterxml.jackson.core.type.TypeReference, org.slf4j.Logger, org.slf4j.LoggerFactory, io.github.llm4j.model.LLMResponse
> Implementation of a ReAct (Reasoning and Acting) agent. The agent uses a loop of Thought -> Action -> Observation to solve tasks.

#### File: `src/main/java/io/github/llm4j/agent/Tool.java`
- **Classes**: Tool
> Interface for tools that can be used by the ReAct agent. Tools are functions that the agent can invoke during its reasoning process. Returns the name of the tool. This name will be used by the agent

#### File: `src/main/java/io/github/llm4j/agent/tools/CalculatorTool.java`
- **Classes**: CalculatorTool, ExpressionParser
- **Major Functions**: getName, getDescription, execute, evaluate, nextChar, eat, parse, parseExpression, parseTerm, parseFactor, if
- **Dependencies**: io.github.llm4j.agent.Tool
> A calculator tool that can evaluate basic mathematical expressions.

#### File: `src/main/java/io/github/llm4j/agent/tools/CurrentTimeTool.java`
- **Classes**: CurrentTimeTool
- **Major Functions**: CurrentTimeTool, CurrentTimeTool, getName, getDescription, execute
- **Dependencies**: java.time.LocalDateTime, java.time.ZoneId, io.github.llm4j.agent.Tool, java.time.format.DateTimeFormatter
> A tool that returns the current date and time.

#### File: `src/main/java/io/github/llm4j/agent/tools/EchoTool.java`
- **Classes**: EchoTool
- **Major Functions**: getName, getDescription, execute
- **Dependencies**: io.github.llm4j.agent.Tool
> A simple echo tool that returns the input. Useful for testing.

#### File: `src/main/java/io/github/llm4j/agent/tools/openapi/OpenAPIEndpoint.java`
- **Classes**: OpenAPIEndpoint, Builder
- **Major Functions**: OpenAPIEndpoint, getPath, getMethod, getOperationId, getSummary, getDescription, getParameters, getRequestBodySchema, getResponseSchema, builder, path, method, operationId, summary, description
- **Dependencies**: java.util.List, java.util.Map
> Represents a single API endpoint from an OpenAPI specification.

#### File: `src/main/java/io/github/llm4j/agent/tools/openapi/OpenAPIParameter.java`
- **Classes**: OpenAPIParameter, Builder
- **Major Functions**: OpenAPIParameter, getName, getIn, getDescription, isRequired, getType, getFormat, getDefaultValue, builder, name, in, description, required, type, format
> Represents a parameter for an API endpoint.

#### File: `src/main/java/io/github/llm4j/agent/tools/openapi/OpenAPIParser.java`
- **Classes**: OpenAPIParser
- **Major Functions**: parse, buildSpec, extractEndpoints, buildEndpoint
- **Dependencies**: java.util.HashMap, io.swagger.v3.oas.models.OpenAPI, io.swagger.v3.oas.models.PathItem, io.swagger.v3.oas.models.servers.Server, java.util.stream.Collectors, io.swagger.v3.oas.models.parameters.Parameter, java.util.ArrayList, io.swagger.v3.oas.models.Operation, io.swagger.v3.parser.core.models.SwaggerParseResult, io.swagger.v3.parser.core.models.ParseOptions, java.util.List, io.swagger.v3.oas.models.media.Schema, java.util.Map, io.swagger.v3.parser.OpenAPIV3Parser
> Parser for OpenAPI/Swagger specifications.

#### File: `src/main/java/io/github/llm4j/agent/tools/openapi/OpenAPISpec.java`
- **Classes**: OpenAPISpec, Builder
- **Major Functions**: OpenAPISpec, getTitle, getVersion, getDescription, getServers, getEndpoints, getSecuritySchemes, builder, title, version, description, servers, endpoints, securitySchemes, build
- **Dependencies**: java.util.List, java.util.Map
> Represents a parsed OpenAPI specification.

#### File: `src/main/java/io/github/llm4j/agent/tools/openapi/OpenAPITool.java`
- **Classes**: OpenAPITool, Builder
- **Major Functions**: OpenAPITool, getName, getDescription, execute, findEndpoint, executeRequest, if, builder, name, specLocation, spec, apiKeyAuth, headerAuth, build
- **Dependencies**: java.util.HashMap, io.github.llm4j.agent.Tool, java.net.http.HttpClient, java.net.URI, java.net.http.HttpRequest, java.util.Map, java.net.http.HttpResponse, java.nio.charset.StandardCharsets, java.net.URLEncoder, java.util.stream.Collectors
> Dynamic tool that uses OpenAPI specifications to discover and execute API endpoints.

#### File: `src/main/java/io/github/llm4j/config/LLMConfig.java`
- **Classes**: is, LLMConfig, Builder
- **Major Functions**: LLMConfig, getApiKey, getBaseUrl, getTimeout, getConnectTimeout, getRetryPolicy, getDefaultModel, isEnableLogging, builder, equals, hashCode, toString, Builder, apiKey, baseUrl
- **Dependencies**: java.time.Duration, java.util.Objects
> Configuration for LLM client behavior including timeouts, retries, and defaults. This class is immutable and thread-safe.

#### File: `src/main/java/io/github/llm4j/config/RetryPolicy.java`
- **Classes**: is, RetryPolicy, BackoffStrategy, Builder
- **Major Functions**: RetryPolicy, getMaxRetries, getBackoffStrategy, getInitialBackoff, getMaxBackoff, getRetryableStatusCodes, calculateBackoff, isRetryable, defaultPolicy, noRetry, builder, equals, hashCode, toString, Builder
- **Dependencies**: java.time.Duration, java.util.Objects, java.util.Set, java.util.HashSet, java.util.Collections
> Defines the retry behavior for failed API requests. This class is immutable and thread-safe.

#### File: `src/main/java/io/github/llm4j/examples/AdvancedConfigExample.java`
- **Classes**: AdvancedConfigExample
- **Major Functions**: main, demonstrateErrorHandling
- **Dependencies**: io.github.llm4j.exception.*, io.github.llm4j.LLMClient, io.github.llm4j.model.LLMRequest, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, java.time.Duration, io.github.llm4j.config.RetryPolicy, io.github.llm4j.config.LLMConfig, io.github.llm4j.model.LLMResponse
> Example demonstrating advanced configuration options. Check for API key

#### File: `src/main/java/io/github/llm4j/examples/ReActAgentExample.java`
- **Classes**: ReActAgentExample
- **Major Functions**: main, printResult
- **Dependencies**: io.github.llm4j.LLMClient, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, io.github.llm4j.agent.tools.CalculatorTool, io.github.llm4j.agent.AgentResult, io.github.llm4j.agent.ReActAgent, io.github.llm4j.agent.tools.CurrentTimeTool, io.github.llm4j.config.LLMConfig
> Example demonstrating the ReAct agent with multiple tools. Check for API key

#### File: `src/main/java/io/github/llm4j/exception/AuthenticationException.java`
- **Classes**: AuthenticationException
- **Major Functions**: AuthenticationException, AuthenticationException
> Exception thrown when authentication fails (e.g., invalid API key).

#### File: `src/main/java/io/github/llm4j/exception/InvalidRequestException.java`
- **Classes**: InvalidRequestException
- **Major Functions**: InvalidRequestException, InvalidRequestException
> Exception thrown when a request is invalid or malformed.

#### File: `src/main/java/io/github/llm4j/exception/LLMException.java`
- **Classes**: LLMException
- **Major Functions**: LLMException, LLMException, LLMException, LLMException, getStatusCode
> Base exception for all LLM-related errors.

#### File: `src/main/java/io/github/llm4j/exception/ProviderException.java`
- **Classes**: ProviderException
- **Major Functions**: ProviderException, ProviderException, ProviderException, getProviderName
> Exception thrown for provider-specific errors.

#### File: `src/main/java/io/github/llm4j/exception/RateLimitException.java`
- **Classes**: RateLimitException
- **Major Functions**: RateLimitException, RateLimitException, getRetryAfterSeconds
> Exception thrown when rate limits are exceeded.

#### File: `src/main/java/io/github/llm4j/http/HttpClientWrapper.java`
- **Classes**: HttpClientWrapper
- **Major Functions**: HttpClientWrapper, post, get, createStreamingCall, executeWithRetry, close
- **Dependencies**: java.time.Duration, java.util.Objects, io.github.llm4j.config.RetryPolicy, okhttp3.*, java.io.IOException, io.github.llm4j.exception.LLMException, org.slf4j.Logger, org.slf4j.LoggerFactory
> Wrapper around OkHttp client with retry logic and logging.

#### File: `src/main/java/io/github/llm4j/model/LLMRequest.java`
- **Classes**: is, LLMRequest, Builder
- **Major Functions**: LLMRequest, validate, getMessages, getModel, getTemperature, getMaxTokens, getTopP, getStopSequences, getAdditionalParameters, builder, equals, hashCode, toString, Builder, messages
- **Dependencies**: java.util.HashMap, java.util.Objects, java.util.ArrayList, java.util.Collections, java.util.List, java.util.Map
> Represents a request to an LLM. This class is immutable and thread-safe.

#### File: `src/main/java/io/github/llm4j/model/LLMResponse.java`
- **Classes**: is, LLMResponse, FinishReason, TokenUsage, Builder
- **Major Functions**: getValue, fromValue, LLMResponse, getContent, getModel, getTokenUsage, getFinishReason, getMetadata, builder, equals, hashCode, toString, TokenUsage, getPromptTokens, getCompletionTokens
- **Dependencies**: java.util.HashMap, java.util.Objects, java.util.Map, java.util.Collections
> Represents a response from an LLM. This class is immutable and thread-safe.

#### File: `src/main/java/io/github/llm4j/model/Message.java`
- **Classes**: Message, Role, Builder
- **Major Functions**: getValue, fromValue, Message, getRole, getContent, getName, builder, system, user, assistant, equals, hashCode, toString, Builder, role
- **Dependencies**: java.util.Objects
> Represents a message in a conversation with an LLM. Messages can have different roles (system, user, assistant) and contain text content.

#### File: `src/main/java/io/github/llm4j/provider/LLMProvider.java`
- **Classes**: LLMProvider
- **Dependencies**: io.github.llm4j.model.LLMRequest, io.github.llm4j.model.LLMResponse, java.util.stream.Stream
> Service Provider Interface (SPI) for implementing LLM provider integrations. Each provider (OpenAI, Anthropic, Google, etc.) implements this interface to handle provider-specific API calls and transfo

#### File: `src/main/java/io/github/llm4j/provider/google/GoogleProvider.java`
- **Classes**: GoogleProvider
- **Major Functions**: GoogleProvider, chat, chatStream, getProviderName, validate, listModels, getFirstAvailableModel, buildRequestJson, buildHeaders, parseResponse, if
- **Dependencies**: io.github.llm4j.exception.AuthenticationException, io.github.llm4j.model.LLMRequest, com.fasterxml.jackson.databind.node.ObjectNode, com.fasterxml.jackson.databind.ObjectMapper, io.github.llm4j.model.LLMResponse, java.util.Objects, com.fasterxml.jackson.databind.node.ArrayNode, io.github.llm4j.model.Message, io.github.llm4j.exception.InvalidRequestException, java.util.stream.Stream, io.github.llm4j.provider.LLMProvider, okhttp3.Headers, io.github.llm4j.http.HttpClientWrapper, io.github.llm4j.exception.ProviderException, java.io.IOException

#### File: `src/test/java/io/github/llm4j/DefaultLLMClientTest.java`
- **Classes**: DefaultLLMClientTest
- **Major Functions**: setUp, testChatDelegatesToProvider, testNullProviderThrows, testNullRequestThrows, testValidateCalledOnConstruction, testGetProviderName
- **Dependencies**: io.github.llm4j.model.LLMRequest, org.junit.jupiter.api.BeforeEach, org.junit.jupiter.api.Test, io.github.llm4j.provider.LLMProvider, org.mockito.MockitoAnnotations, org.mockito.Mock, io.github.llm4j.model.LLMResponse

#### File: `src/test/java/io/github/llm4j/agent/ReActAgentTest.java`
- **Classes**: ReActAgentTest
- **Major Functions**: setUp, testAgentWithSingleToolCall, testAgentWithMultipleToolCalls, testAgentWithUnknownTool, testAgentMaxIterations, testAgentSystemPromptContainsTools, testBuilderValidation, createResponse
- **Dependencies**: io.github.llm4j.model.LLMRequest, io.github.llm4j.LLMClient, org.junit.jupiter.api.BeforeEach, io.github.llm4j.agent.tools.CalculatorTool, org.junit.jupiter.api.Test, org.mockito.MockitoAnnotations, io.github.llm4j.agent.tools.EchoTool, org.mockito.Mock, io.github.llm4j.model.LLMResponse, org.mockito.ArgumentCaptor

#### File: `src/test/java/io/github/llm4j/agent/tools/CalculatorToolTest.java`
- **Classes**: CalculatorToolTest
- **Major Functions**: testSimpleAddition, testMultiplication, testComplexExpression, testDecimalNumbers, testEmptyInput, testToolMetadata
- **Dependencies**: org.junit.jupiter.api.Test

#### File: `src/test/java/io/github/llm4j/config/RetryPolicyTest.java`
- **Classes**: RetryPolicyTest
- **Major Functions**: testDefaultPolicy, testNoRetryPolicy, testExponentialBackoff, testLinearBackoff, testFixedBackoff, testMaxBackoff, testCustomRetryableStatusCodes
- **Dependencies**: java.time.Duration, org.junit.jupiter.api.Test

#### File: `src/test/java/io/github/llm4j/integration/GoogleIntegrationTest.java`
- **Classes**: GoogleIntegrationTest
- **Major Functions**: setUp, testSimpleFactualQuestion, testMathQuestion, testWithSystemMessage, testMultiTurnConversation, testShortSummary, testLongerExplanation, testCreativeTask, testListGeneration, testLowTemperature, testComplexQuestionWithContext, summary
- **Dependencies**: io.github.llm4j.model.LLMRequest, io.github.llm4j.LLMClient, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, org.junit.jupiter.api.*, io.github.llm4j.config.LLMConfig, io.github.llm4j.model.LLMResponse
> Integration tests for Google Gemini provider. These tests make real API calls and require GOOGLE_API_KEY to be set.

#### File: `src/test/java/io/github/llm4j/integration/ReActAgentIntegrationTest.java`
- **Classes**: ReActAgentIntegrationTest
- **Major Functions**: setUp, testAgentBasicFunctionality, testCalculatorTool, testCurrentTimeTool, testEchoTool, testMultipleTools, testMultiStepReasoning, testIterationLimit, testUnknownToolHandling, testToolErrorRecovery, Tool, getName, getDescription, execute, testStepTracking
- **Dependencies**: io.github.llm4j.LLMClient, io.github.llm4j.DefaultLLMClient, io.github.llm4j.provider.google.GoogleProvider, io.github.llm4j.agent.tools.CalculatorTool, org.junit.jupiter.api.*, io.github.llm4j.agent.Tool, io.github.llm4j.agent.AgentResult, io.github.llm4j.agent.tools.EchoTool, io.github.llm4j.agent.ReActAgent, io.github.llm4j.agent.tools.CurrentTimeTool, io.github.llm4j.config.LLMConfig
> Integration tests for ReAct Agent with real Google Gemini API. These tests verify the agent can properly use tools through reasoning loops.

#### File: `src/test/java/io/github/llm4j/model/LLMRequestTest.java`
- **Classes**: LLMRequestTest
- **Major Functions**: testBasicRequest, testRequestWithAllParameters, testEmptyMessagesThrows, testInvalidTemperatureThrows, testInvalidMaxTokensThrows, testInvalidTopPThrows, testImmutability
- **Dependencies**: org.junit.jupiter.api.Test

#### File: `src/test/java/io/github/llm4j/model/LLMResponseTest.java`
- **Classes**: LLMResponseTest
- **Major Functions**: testBasicResponse, testResponseWithTokenUsage, testResponseWithFinishReason, testFinishReasonFromValue, testResponseWithMetadata, testTokenUsageEquality
- **Dependencies**: org.junit.jupiter.api.Test

#### File: `src/test/java/io/github/llm4j/model/MessageTest.java`
- **Classes**: MessageTest
- **Major Functions**: testMessageBuilder, testFactoryMethods, testNullRoleThrows, testNullContentThrows, testEquality, testRoleFromValue, testInvalidRoleThrows
- **Dependencies**: org.junit.jupiter.api.Test

#### File: `wiki/Creating-Custom-Tools.md`
> Creating Custom Tools Tool Interface Basic Custom Tool

#### File: `wiki/Getting-Started.md`
> Getting Started with Gemini ReAct Java Prerequisites Installation

#### File: `wiki/Home.md`
> 🚀 Gemini ReAct Java The Production-Ready Java Client for Google Gemini 🌟 Why Gemini ReAct Java?

#### File: `wiki/LLM_CONTEXT.md`
> Gemini ReAct Java Library Context 1. Overview gemini-react-java** is a Java library for interacting with Google Gemini. It provides a unified `LLMClient` interface and a `ReActAgent` framework for bui

#### File: `wiki/OpenAPI-Tool.md`
> OpenAPI Tool Features 1. Add Dependency

#### File: `wiki/ReAct-Agent.md`
> ReAct Agent Guide What is ReAct? Basic Usage