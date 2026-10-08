export const API_BASE = 'http://localhost:8080/api/project';

export const startProject = async (userIdea) => {
    const response = await fetch(`${API_BASE}/start`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: userIdea,
    });

    if (!response.ok) {
        throw new Error('Failed to start project');
    }

    return response.json();
};

export const connectToStream = (projectId) => {
    return new EventSource(`${API_BASE}/${projectId}/stream`);
};
