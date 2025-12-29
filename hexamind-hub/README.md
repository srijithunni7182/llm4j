# Hexamind Hub

<img src="src/main/resources/static/images/hexamind_logo.png" width="200" alt="Hexamind Hub Logo">

**Hexamind Hub** (formerly Multi-Agent Collaboration Platform) is a cutting-edge platform for multi-agent collaboration, where a team of specialized AI agents work together to solve complex, multifaceted problems.

## 💡 The Idea

In traditional LLM interactions, you get a single perspective. Hexamind Hub breaks this paradigm by assembling a **digital boardroom** of expert personas. Just as a CEO wouldn't make a major decision without consulting their technical, financial, and creative leads, Hexamind Hub simulates this collaborative intelligence.

Each agent is powered by the `gemini-react-java` library and configured with a distinct personality, expertise, and set of constraints. They don't just answer; they **debate**.

## 👥 The Agents

Meet the team of 6 specialized personas:

1. **Technical Analyst (`Alex`)**:
    * *Focus*: Feasibility, architecture, data, and implementation details.
    * *Motto*: "Show me the code and the data."
2. **Business Consultant (`Jordan`)**:
    * *Focus*: Strategy, ROI, market fit, and business viability.
    * *Motto*: "Does it make business sense?"
3. **Creative Thinker (`Sasha`)**:
    * *Focus*: Innovation, user experience, branding, and "wild ideas".
    * *Motto*: "What if we broke the rules?"
4. **Research Scientist (`Dr. Aris`)**:
    * *Focus*: Evidence, academic backing, theoretical soundness, and citations.
    * *Motto*: "What does the research say?"
5. **Customer Advocate (`Casey`)**:
    * *Focus*: User needs, accessibility, pain points, and customer satisfaction.
    * *Motto*: "But how does the user feel?"
6. **Cynical Skeptic (`Rahul`)**:
    * *Focus*: Risk identification, logical fallacies, and challenging assumptions.
    * *Motto*: "What if this fails? Where is the real data?"
    * *Role*: Rahul is the "Devil's Advocate" who ensures the group doesn't fall into groupthink.

## 🔄 How It Works

The platform orchestrates a structured **5-Round Debate Process**:

### Round 1: Initial Analysis & Fact-Checking

Each agent independently analyzes the problem. Crucially, they perform **literal verification** using web tools to debunk any fabricated or hallucinated terms in the prompt.

### Round 2: Arguments

Agents present their main arguments based on their verified analysis, establishing their core positions.

### Round 3: Critique

Agents review each other's arguments and offer objective critiques, pointing out logical fallacies, missing data, or potential downsides.

### Round 4: Rebuttal

Agents defend their positions against the specific critiques they received, clarifying misunderstandings or refining their arguments.

### Round 5: Final Refinement

Agents provide a final response considering all viewpoints shared during the debate.

### Consensus

Finally, the system acts as a "Master Coordinator" to synthesize all expert opinions. It:

* Merges perspectives into a coherent narrative.
* Addresses critical risks raised (especially by Rahul).
* Synthesizes a final, unified recommendation.

## ✨ Key Enhancements

### 🔍 Search Optimization & Fallback

To ensure maximum reliability and efficiency, the hub uses a tiered search strategy:

1. **SerpAPI**: Primary high-quality search (requires API key).
2. **DuckDuckGo**: Reliable free fallback for instant answers.
3. **Google Custom Search**: Secondary fallback.
All results are managed by a **Cross-Agent Caching Layer**, which ensures that if one agent searches for a topic, all other agents can access that information instantly without making redundant API calls.

### ⏰ Temporal Awareness

Agents are no longer "stuck in time." Every agent is automatically aware of the current date and time via:
* **Prompt Injection**: The current time is injected into every agent's system prompt during initialization.
* **DateTimeTool**: Agents can explicitly use this tool to get the current timestamp in RFC 1123 format.

### ⚡ UI Stability

Optimized for large-scale analysis:
* **WebSocket Tuning**: Increased server-side limits (512KB messages, 1MB buffer) to prevent truncation of deep-dive expert responses.
* **Heartbeat Management**: Enhanced client-side stability for long-running collaborative sessions.

## 🚀 Setup & Running

### Prerequisites

* **Java 17** or higher
* **Maven** 3.8+
* **Google Gemini API Key** (Required - [Get one here](https://aistudio.google.com/))
* **SerpAPI API Key** (Highly Recommended for high-quality search)
* **Google Custom Search Engine ID (CX)** (Optional fallback)

### Step 1: Clone & Build

First, build the core `gemini-react-java` library:

```bash
cd gemini-react-java
mvn clean install -DskipTests
cd ..
```

Then, build the Hexamind Hub platform:

```bash
cd hexamind-hub
mvn clean package -DskipTests
```

### Step 2: Configure Environment

You need to set your API keys. You can do this by exporting them in your terminal, or by creating a `secrets.sh` file in the `hexamind-hub` directory (this file is git-ignored for safety).

**Option A: Using secrets.sh (Recommended)**

1. Copy the example template:

    ```bash
    cp hexamind-hub/example_env.sh hexamind-hub/secrets.sh
    ```

2. Edit `hexamind-hub/secrets.sh` and add your actual keys:

    ```bash
    export GOOGLE_API_KEY="AIzaSy..."
    export SERPAPI_API_KEY="your_serp_key..."
    export GOOGLE_SEARCH_CX="012345..."
    ```

**Option B: Exporting Variables**

```bash
export GOOGLE_API_KEY="your_actual_key"
export GOOGLE_SEARCH_CX="your_search_cx_id"
```

### Step 3: Run the Hub

Launch the platform using the provided script:

```bash
./hexamind-hub/launch.sh
```

Or manually with Maven:

```bash
cd hexamind-hub
mvn spring-boot:run
```

### Step 4: Access the Boardroom

Open your browser and navigate to:
**<http://localhost:8080>**

## Architecture

```
Frontend (HTML/JS + WebSocket)
         ↓
Spring Boot REST API
         ↓
MultiAgentOrchestrator
         ↓
6 AI Agents (llm4j + Personas)
         ↓
Google Gemini API
```

## License

MIT
