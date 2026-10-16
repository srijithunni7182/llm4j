// The UI is served by the factory's own host, so every call is to the same origin.
export const API_BASE = '/api/project';

async function failure(response) {
    const text = await response.text().catch(() => '');
    return new Error(text || `Request failed (${response.status})`);
}

export const startProject = async (userIdea) => {
    const response = await fetch(`${API_BASE}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: userIdea,
    });
    if (!response.ok) throw await failure(response);
    return response.json();
};

export const connectToStream = (projectId) => new EventSource(`${API_BASE}/${projectId}/stream`);

// A question from the factory (an approval, "carry on anyway?", a budget) is answered with exactly one of its choices.
export const submitAnswer = async (projectId, text) => {
    const response = await fetch(`${API_BASE}/${projectId}/answer`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: text,
    });
    if (!response.ok) throw await failure(response);
};

export const stopProject = async (projectId) => {
    const response = await fetch(`${API_BASE}/${projectId}/stop`, { method: 'POST' });
    if (!response.ok) throw await failure(response);
};
