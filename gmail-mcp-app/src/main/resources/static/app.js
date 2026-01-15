const messagesDiv = document.getElementById('messages');
const messageInput = document.getElementById('userInput');
const sendBtn = document.getElementById('sendBtn');
const toolsList = document.getElementById('toolsList');

// Load tools on startup
fetchTools();

// Handle text input enter key
messageInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
        sendMessage();
    }
});

// Handle button click
sendBtn.addEventListener('click', sendMessage);

async function sendMessage() {
    const text = messageInput.value.trim();
    if (!text) return;

    addMessage(text, 'user');
    messageInput.value = '';
    sendBtn.disabled = true;

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text }) // Spring requires JSON body even for SSE endpoint usually
        });

        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();

        // Container for thoughts
        let thoughtsContainer = document.createElement('div');
        thoughtsContainer.className = 'thoughts-container';
        let thoughtsDetails = document.createElement('details');
        // thoughtsDetails.open = true; // Auto-open if you want
        let thoughtsSummary = document.createElement('summary');
        thoughtsSummary.innerText = "🤔 Thinking...";
        thoughtsDetails.appendChild(thoughtsSummary);
        let thoughtsList = document.createElement('div');
        thoughtsList.className = 'thoughts-list';
        thoughtsDetails.appendChild(thoughtsList);
        thoughtsContainer.appendChild(thoughtsDetails);
        messagesDiv.appendChild(thoughtsContainer);

        let buffer = '';

        while (true) {
            const { value, done } = await reader.read();
            if (done) break;

            buffer += value;
            const chunks = buffer.split('\n\n');
            buffer = chunks.pop(); // Keep incomplete chunk

            for (const chunk of chunks) {
                const lines = chunk.split('\n');
                let eventType = 'message';
                let data = '';

                for (const line of lines) {
                    if (line.startsWith('event:')) {
                        eventType = line.slice(6).trim();
                    } else if (line.startsWith('data:')) {
                        data = line.slice(5).trim();
                    }
                }

                if (!data) continue;

                let parsedData = data;
                try {
                    // Backend now wraps strings in JSON {text: "..."} or sends object for action
                    const json = JSON.parse(data);
                    parsedData = json.text || json;
                    // If it was action, it stays object. If it was wrapped string, we get .text
                    // Wait, if it's action, it's {tool:..., input:...}. .text is undefined. parsedData becomes json object.
                    // If it's answer, it's {text: "..."}. parsedData becomes "..."
                    // If it's thought, it's {text: "..."}. parsedData becomes "..."
                } catch (e) {
                    // Fallback to raw string if not JSON
                    console.warn("Failed to parse SSE JSON", e);
                }

                if (eventType === 'thought') {
                    addThought(thoughtsList, 'Thought', parsedData);
                    thoughtsSummary.innerText = "🤔 Agent Thought Process (Active)";
                } else if (eventType === 'action') {
                    // action data is object {tool:..., input:...}
                    addThought(thoughtsList, 'Action', `${parsedData.tool} (Input: ${parsedData.input})`);
                } else if (eventType === 'observation') {
                    addThought(thoughtsList, 'Result', parsedData);
                } else if (eventType === 'answer') {
                    addMessage(parsedData, 'assistant');
                    thoughtsSummary.innerText = "✅ Agent Thought Process (Complete)";
                } else if (eventType === 'error') {
                    addMessage('Error: ' + parsedData, 'system');
                }
            }
        }

    } catch (e) {
        addMessage('Error: ' + e.message, 'system');
    } finally {
        sendBtn.disabled = false;
        messagesDiv.scrollTop = messagesDiv.scrollHeight;
    }
};

function addMessage(text, sender) {
    const div = document.createElement('div');
    div.classList.add('message', sender);

    const content = document.createElement('div');
    content.classList.add('message-content');

    // Simple verification for markdown-style bold
    if (sender === 'assistant') {
        content.innerHTML = parseMarkdown(text);
    } else {
        content.textContent = text;
    }

    div.appendChild(content);
    messagesDiv.appendChild(div);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function addThought(container, type, text) {
    const div = document.createElement('div');
    div.className = 'thought-step';

    let labelClass = 'thought-text';
    if (type === 'Action') labelClass = 'action-text';
    if (type === 'Result') labelClass = 'observation-text';

    div.innerHTML = `<div class="${labelClass}"><strong>${type}:</strong> ${text}</div>`;
    container.appendChild(div);
    // Auto-scroll thoughts
    container.scrollTop = container.scrollHeight;
}

function parseMarkdown(text) {
    let html = text
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\n/g, '<br>')
        .replace(/\* (.*?)(<br>|$)/g, '<li>$1</li>');

    if (html.includes('<li>')) {
        html = html.replace(/((<li>.*<\/li>)+)/g, '<ul>$1</ul>');
    }
    return html;
}

async function fetchTools() {
    try {
        const response = await fetch('/api/tools');
        const tools = await response.json();
        toolsList.innerHTML = tools.map(t =>
            `<div class="tool-item">
                <div class="tool-name">${t.name}</div>
                <div class="tool-desc">${t.description || ''}</div>
            </div>`
        ).join('');
    } catch (e) {
        console.error("Failed to load tools", e);
    }
}
