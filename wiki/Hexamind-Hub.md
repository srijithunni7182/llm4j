# Hexamind Hub

![Hexamind Hub Logo](../hexamind-hub/src/main/resources/static/images/hexamind_logo.png)

**Hexamind Hub** is a cutting-edge platform for multi-agent collaboration, where a team of specialized AI agents work together to solve complex, multifaceted problems.

## 💡 The Idea

In traditional LLM interactions, you get a single perspective. Hexamind Hub breaks this paradigm by assembling a **digital boardroom** of expert personas. Just as a CEO wouldn't make a major decision without consulting their technical, financial, and creative leads, Hexamind Hub simulates this collaborative intelligence.

Each agent is powered by the `gemini-react-java` library and configured with a distinct personality, expertise, and set of constraints. They don't just answer; they **debate**.

## 👥 The Agents

Meet the team:

1. **Technical Analyst (`Aris`)**:
    * *Focus*: Feasibility, architecture, data, and implementation details.
    * *Motto*: "Show me the code and the data."
2. **Business Consultant (`Sasha`)**:
    * *Focus*: Strategy, ROI, market fit, and scalability.
    * *Motto*: "Does it make business sense?"
3. **Creative Thinker (`Jordan`)**:
    * *Focus*: Innovation, user experience, branding, and "wild ideas".
    * *Motto*: "What if we broke the rules?"
4. **Research Scientist (`Alex`)**:
    * *Focus*: Evidence, academic backing, theoretical soundness, and citations.
    * *Motto*: "What does the research say?"
5. **Customer Advocate (`Casey`)**:
    * *Focus*: User needs, accessibility, pain points, and customer satisfaction.
    * *Motto*: "But how does the user feel?"

## 🔄 How It Works

The platform orchestrates a structured 3-round debate process:

### Round 1: Independent Analysis

Each agent receives the user's problem statement. They retreat to their "corners" and analyze the problem strictly through the lens of their persona.

* *Output*: 5 distinct initial take-aways.

### Round 2: The Debate

Agents review the analyses of their peers. They can:

* **Critique**: Challenge assumptions made by others (e.g., The Analyst might tell the Creative that their idea is technically impossible).
* **Support**: Build upon good ideas.
* **Counter**: Offer alternative solutions.

### Round 3: Consensus & Synthesis

The system (or a designated moderator agent) reviews the entire thread of arguments. It identifies the strongest points, reconciles conflicts, and produces a final, comprehensive recommendation that balances technical feasibility, business viability, and user experience.

## 🚀 Setup & Running

Follow these steps to get your own Hexamind boardroom running.

### Prerequisites

* **Java 17** or higher
* **Maven** 3.8+
* **Google Gemini API Key** (Get one [here](https://aistudio.google.com/))
* **Google Custom Search Engine ID (CX)** (Optional, for web search capabilities)

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

Enter a complex problem (e.g., *"We want to launch a coffee subscription service for developers. How should we price and market it?"*) and watch the team go to work!
