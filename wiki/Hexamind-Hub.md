# Hexamind Hub

**Hexamind Hub** is a platform for multi-agent collaboration, enabling 5 AI agents with distinct personas to debate and solve complex problems.

## Overview

Hexamind Hub leverages the `gemini-react-java` library to orchestrate a team of AI agents. Each agent has a specific persona and role, contributing unique perspectives to the problem-solving process.

## The Agents

1. **Technical Analyst**: Focuses on feasibility, data, and implementation details.
2. **Business Consultant**: Focuses on strategy, ROI, market fit, and business viability.
3. **Creative Thinker**: Focuses on innovation, user experience, and "out of the box" ideas.
4. **Research Scientist**: Focuses on evidence, academic backing, and theoretical soundness.
5. **Customer Advocate**: Focuses on user needs, accessibility, and customer satisfaction.

## How it Works

The collaboration process consists of three rounds:

1. **Analysis Round**: Each agent analyzes the user's problem statement from their persona's perspective.
2. **Debate Round**: Agents review other agents' analyses and offer critiques or counter-arguments.
3. **Consensus Round**: The system synthesizes all viewpoints into a final, comprehensive recommendation.

## Architecture

The application is built with:

* **Backend**: Spring Boot
* **AI Engine**: `gemini-react-java` linking to Google Gemini Pro/Flash
* **Frontend**: Vanilla JavaScript + WebSocket for real-time streaming

## Running the Hub

See the [Project README](../hexamind-hub/README.md) for detailed instructions.

```bash
cd hexamind-hub
mvn spring-boot:run
```
