# 🐈 Kingini (കിങ്ങിണി)

**The Wise & Whimsical Voice Agent of Kerala**

Kingini is an interactive, voice-enabled AI companion designed for children. Living in a digital recreation of a traditional Kerala Tharavad, she speaks authentic Malayalam, answers questions, and brings the charm of a wise grandmother's tales in the form of a cute, animated cat.

![Kingini Screenshot](src/main/resources/static/images/cat.png)

## 📖 The Legend of Kingini

Long ago, in the heart of a verdant village in Kerala, stood an ancient Tharavad known as *Arivu Pura* (The House of Knowledge). It was home to Mutthassi, a legendary storyteller whose tales were said to hold the secrets of the universe.

One stormy monsoon night, a tiny, shivering kitten wandered onto the verandah. Mutthassi took her in, named her **Kingini** (Little Bell), and nursed her back to health. Kingini wasn't just any cat; she was an avid listener. For years, she sat by Mutthassi's side, absorbing every fable, proverb, and song.

Legend has it that on a magical Thiruvonam night, touched by the moonlight and the spirit of the stories, Kingini was granted a miracle: the gift of speech.

Though Mutthassi has long since passed, Kingini remains in the Tharavad. She has taken a vow to keep the light of knowledge burning. She waits patiently on the cool floor, ready to chat with any child who visits, answering their curious questions with the wisdom of the ages and the playfulness of a kitten.

## ✨ Features

*   **Authentic Malayalam Conversations**: Powered by **Sarvam AI**'s specialized models (`saaras` for Hearing, `bulbul` for Speech).
*   **Immersive Interface**: A visually stunning UI featuring a detailed Tharavad background and a transparent, animated cat avatar.
*   **Realistic Lip-Sync**: Kingini's mouth moves in sync with her speech, making the interaction feel alive.
*   **Contextual Persona**: Kingini knows she is a cat! She sprinkles her speech with "Meow" (മ്യാവൂ) and maintains a friendly, educational tone.
*   **Voice-First Interaction**: Just tap the microphone and speak. No typing required!

## 🛠️ Tech Stack

*   **Backend**: Java (Spring Boot)
*   **AI Orchestration**: [ai-agent4j](https://github.com/srijithunni7182/llm4j) (formerly llm4j)
*   **AI Provider**: Sarvam AI (STT, LLM, TTS)
*   **Frontend**: HTML5, CSS3, Vanilla JavaScript
*   **Audio**: Web Audio API

## 🚀 Getting Started

### Prerequisites

*   Java 17 or later
*   Maven
*   A **Sarvam AI** API Key

### Installation

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/kingini.git
    cd kingini
    ```

2.  **Set your API Key:**
    You can export it as an environment variable:
    ```bash
    export SARVAM_API_KEY=your_api_key_here
    ```
    Or pass it as a system property when running.

3.  **Run the Application:**
    ```bash
    mvn spring-boot:run
    ```

4.  **Meet Kingini:**
    Open your browser and navigate to:
    [http://localhost:8080](http://localhost:8080)

## 🧙‍♂️ How It Works

1.  **Listen**: The browser captures your voice and sends it to the backend.
2.  **Transcribe**: The backend uses **Sarvam STT** to convert your Malayalam speech to text.
3.  **Think**: The **ReAct Agent** (powered by `ai-agent4j`) processes the text. Kingini's persona is injected via a system prompt to ensure she stays in character.
4.  **Speak**: The response is converted back to audio using **Sarvam TTS** (Voice: *Ritu*) and played back in the browser with synchronized animations.

## 🤝 Contributing

Kingini loves making new friends! If you have ideas to make her smarter, cuter, or more talkative, feel free to open an issue or submit a pull request.

---
*Made with ❤️ and 🥥 in Kerala*
