# Nirmaan Yantra: The AI Software Factory

> **"You dream it. We build it."**

**Nirmaan Yantra** is an autonomous software development environment where a team of specialized AI agents collaborates to build, test, and deploy software based on your natural language specifications.

It is built on top of [llm4j](../README.md), demonstrating the power of the **ai-agent4j** framework.

---

## 🤖 The Crew

Meet the team responsible for building your software. Each agent has a distinct role and personality.

<div align="center">

| **Rishi (The Architect)** | **Vihaan (The Builder)** | **Dhruv (The Realist)** |
| :---: | :---: | :---: |
| <img src="nirmaan-yantra-ui/src/assets/personas/Rishi.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Vihaan.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Dhruv.png" width="150"/> |
| **Role:** Product & Solutions<br>Converts concepts into Specs. | **Role:** Lead Developer<br>Writes code and fixes bugs. | **Role:** Build Engineer<br>Executes build & rejects failures. |

| **Drishti (The Eye)** | **Vishnu (The Gatekeeper)** | **Aditi (The Visionary)** |
| :---: | :---: | :---: |
| <img src="nirmaan-yantra-ui/src/assets/personas/Drishti.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Vishnu.png" width="150"/> | <img src="nirmaan-yantra-ui/src/assets/personas/Aditi.png" width="150"/> |
| **Role:** QA Engineer<br>Automates E2E scenarios. | **Role:** Release Manager<br>Final approval & sign-off. | **Role:** Business Analyst<br>Understands users & creates PRDs. |

</div>

---

## 🏗️ How it works

Nirmaan Yantra is a [Loom](../../loom/ai-agent4j-loom/README.md) workflow. The whole factory is a few short `.loom` files
(`nirmaan-yantra-server/src/main/resources`), with a small web host that runs it and a React UI that shows it.

```
1 Plan      Aditi (PRD) -> Rishi (spec with the exact commands)
2 Prepare   Vihaan: first failing test  |  Drishti: end to end suite        (written at the same time)
3 Build     red -> Vihaan writes the code -> Fix the unit tests
4 Review    code checks tests exist -> Vishnu -> (one polish if rejected, then Fix again)
5 Assure    clean build (one command, no model) -> Fix the end to end suite -> Fix the unit tests again
6 Release   Vishnu signs off (a person decides if he refuses) -> zip
```

The only loop is **Fix**: run a command, let Vihaan fix the code up to 3 times, then ask a person. Models read and write; code decides
and acts: Dhruv is not an agent but the command runner, every check fails closed, and tests are locked so a fix cannot make a command
pass by weakening its test. Details, safety notes and costs: [nirmaan-yantra-server/README.md](nirmaan-yantra-server/README.md) and
[COST.md](nirmaan-yantra-server/COST.md).

---

## 🚀 Getting Started

### Prerequisites

* Java 17+ and Maven 3+
* Node.js 20.19+ (to build the UI)
* A **Google Gemini API key**, kept in the encrypted secret store (never in a file you commit)

### Run it

1. Store your key (you are asked for it with no echo; it is locked to Google's API host):

    ```bash
    cd nirmaan-yantra-server && mkdir -p .loom
    # create the store once, then set the key (see nirmaan-yantra-server/README.md, "Deploying and observing")
    ```

2. Launch the factory:

    ```bash
    ./start_nirmaan.sh
    ```

3. Open **[http://127.0.0.1:8080](http://127.0.0.1:8080)**, type an idea, and watch the crew work. Decisions the factory needs from you appear in the page.

---

## 🧠 Key Features

* **PRD Generation**: Aditi (Product Manager) transforms raw ideas into structured Product Requirements Documents.
* **Spec Generation**: Rishi (The Architect) converts PRDs into comprehensive technical specifications.
* **Automated Testing**:
  * **TDD**: Tests ensure every line of code has a purpose. Dhruv (Build Engineer) ruthlessly validates every build.
  * **E2E**: Drishti (QA Engineer) writes and validates the full system workflow after implementation.
* **Feedback Loops**:
  * **Self-Healing**: Vihaan (Dev) automatically detects compilation errors, reads build files (like `pom.xml`), and fixes missing dependencies.
  * **Fresh Start**: The system detects when it's stuck in a loop (5 failed attempts) and triggers a "Brain Wipe" to rewrite the implementation from scratch.
* **Code Review**: Agents perform self-review to ensure all assets (docs, scripts) are generated without disrupting the coding logic.
* **Real-Time Visualization**: Watch the agents think, debate, and code in real-time via the React UI.

---

Built with ❤️ using **ai-agent4j**.
