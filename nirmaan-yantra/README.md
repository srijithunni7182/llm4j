# Nirmaan Yantra: The AI Software Factory

> **"You dream it. We build it."**

**Nirmaan Yantra** is an autonomous software development environment where a team of specialized AI agents collaborates to build, test, and deploy software based on your natural language specifications.

It is built on top of [llm4j](../README.md), demonstrating the power of the **Gemini ReAct Java** framework.

---

## 🤖 The Crew

Meet the team responsible for building your software. Each agent has a distinct role and personality.

<div align="center">

| **Rishi (The Architect)** | **Vihaan (The Builder)** | **Dhruv (The Skeptic)** |
| :---: | :---: | :---: |
| <img src="nirmaan-yantra-ui/src/assets/personas/Rishi.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Vihaan.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Dhruv.png" width="150"/> |
| **Role:** Product & Solutions<br>Converts concepts into Specs. | **Role:** Lead Developer<br>Writes code and fixes bugs. | **Role:** QA Engineer<br>Finds what Vihaan broke. |

| **Drishti (The Eye)** | **Vishnu (The Gatekeeper)** | **Aditi (The Support)** |
| :---: | :---: | :---: |
| <img src="nirmaan-yantra-ui/src/assets/personas/Drishti.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Vishnu.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Aditi.png" width="150"/> |
| **Role:** Test Engineer<br>Automates E2E scenarios. | **Role:** Release Manager<br>Final approval & sign-off. | **Role:** User Success<br>Support & Documentation. |

</div>

---

## ⚙️ How It Works (The Workflow)

Nirmaan follows a strict **Test-Driven Development (TDD)** pipeline to ensure reliability.

1. **Spec Phase (Rishi)**: You provide a one-line idea (e.g., *"Build a Snake Game"*). Rishi expands this into a detailed Technical Specification (`SPEC.md`).
2. **Red Phase (Dhruv)**: Dhruv reads the Spec and writes a suite of **failing unit tests**.
3. **Green Phase (Vihaan)**: Vihaan writes the implementation code to make Dhruv's tests pass.
    * *Self-Healing*: If compilation fails, Vihaan searches for dependencies (`pom.xml`) and fixes the code.
    * *Anti-Loop*: If Vihaan gets stuck (5 failed attempts), Nirmaan triggers a **Fresh Start**, discarding the bad code and retrying from the Spec.
4. **Refinement Phase**: The code is polished and optimized.
5. **QA Phase (Drishti)**: Drishti writes and runs **End-to-End (E2E)** tests to verify the application works as a whole.
6. **Sign-Off (Vishnu)**: Vishnu reviews the artifacts and gives the final build approval.

---

## 🚀 Getting Started

### Prerequisites

* Java 17+
* Maven 3+
* Node.js 18+ (for UI)
* **Google Gemini API Key**

### Installation

1. Clone the repository:

    ```bash
    git clone https://github.com/srijithunni7182/llm4j.git
    cd llm4j/nirmaan-yantra
    ```

2. Configure Secrets:
    Create a `secrets.sh` file in `nirmaan-yantra/`:

    ```bash
    export GOOGLE_API_KEY="your-gemini-api-key"
    export GOOGLE_SEARCH_CX="your-search-engine-id" # Optional, for web search
    ```

3. Launch the Factory:

    ```bash
    ./start_nirmaan.sh
    ```

4. Access the UI:
    Open **[http://localhost:5173](http://localhost:5173)** in your browser.

---

## 🧠 Key Features

* **Smart Context**: Agents automatically identify and read relevant build files (e.g., `pom.xml`) to fix missing dependencies.
* **Mixed Content Handling**: Can distinguish between generating code logic (Java) and assets (README, Scripts) during self-review.
* **Fresh Start Strategy**: Automatically detects development dead-ends and "reboots" the implementation process to break infinite loops.
* **Real-Time Visualization**: Watch the agents think, debate, and code in real-time via the React UI.

---

Built with ❤️ using **Gemini ReAct Java**.
