# Hexamind Hub

**Hexamind Hub** (formerly Multi-Agent Collaboration Platform) is a web application where multiple AI agents with different personas collaborate to solve real-world problems.

## Features

- **5 AI Agents with Unique Personas**:
  - Technical Analyst (data-driven)
  - Business Consultant (strategic)
  - Creative Thinker (innovative)
  - Research Scientist (evidence-based)
  - Customer Advocate (user-focused)

- **3-Round Debate Process**:
  1. **Analysis**: Each agent analyzes the problem independently
  2. **Arguments**: Agents present their main arguments
  3. **Responses**: Agents respond to each other's points
  4. **Consensus**: System synthesizes a final recommendation

- **Real-time Visualization**:
  - Watch agents think in real-time
  - See debate timeline with all thoughts
  - View consensus building process
  - Agreement score visualization

## Quick Start

### Prerequisites

- Java 17+
- Maven
- Google API Key (for Gemini)

### 1. Build the llm4j library

```bash
cd gemini-react-java
mvn clean install -DskipTests
cd ..
```

### 2. Set your API key

```bash
export GOOGLE_API_KEY=your_api_key_here
```

### 3. Run the application

```bash
./hexamind-hub/launch.sh
```

Or manually:

```bash
cd hexamind-hub
mvn spring-boot:run
```

### 4. Open in browser

Navigate to: `http://localhost:8080`

## Usage

1. Enter your problem in the text area
2. Click "Start Collaboration"
3. Watch the agents debate in real-time
4. View the final consensus recommendation

## Example Problems

Try these sample problems:

- "How can I improve team productivity in a remote work environment?"
- "What's the best strategy to launch a new mobile app?"
- "How should I approach learning a new programming language?"
- "What factors should I consider when choosing a cloud provider?"

## Architecture

```
Frontend (HTML/JS + WebSocket)
         ↓
Spring Boot REST API
         ↓
MultiAgentOrchestrator
         ↓
5 AI Agents (llm4j + Personas)
         ↓
Google Gemini API
```

## API Endpoints

### REST

- `POST /api/problems` - Submit a problem
- `GET /api/sessions/{id}` - Get session status

### WebSocket

- `/ws` - WebSocket endpoint
- `/topic/session/{id}/thoughts` - Agent thoughts stream
- `/topic/session/{id}/status` - Session status updates
- `/topic/session/{id}/consensus` - Final consensus

## Technology Stack

- **Backend**: Spring Boot 3.2, Java 17
- **AI**: llm4j library with Google Gemini
- **Real-time**: WebSocket (SockJS + STOMP)
- **Frontend**: HTML5, JavaScript, CSS3

## License

MIT
