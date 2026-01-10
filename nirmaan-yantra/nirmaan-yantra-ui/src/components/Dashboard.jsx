import { useEffect, useState, useRef } from 'react';
import { connectToStream } from '../services/api';
import ChatBubble from './ChatBubble';
import ArtifactsPanel from './ArtifactsPanel';

const AGENT_NAMES = ['Aditi', 'Rishi', 'Vihaan', 'Dhruv', 'Drishti', 'Vishnu'];

const Dashboard = ({ projectId, userIdea }) => {
    const [messages, setMessages] = useState([]);
    const [status, setStatus] = useState('Initializing Project...');
    const [isComplete, setIsComplete] = useState(false);
    const messagesEndRef = useRef(null);

    // Helper to parse log into { agent, message }
    const parseLog = (log) => {
        // Check if log starts with Agent name
        // Patterns: "Aditi: ...", "[Aditi] ...", "Aditi ...", or just in the text?
        // Let's assume the server logs format is roughly "[AgentName] Message" or just "Message"
        // If we find an agent name at start, use it.

        const foundAgent = AGENT_NAMES.find(name =>
            log.startsWith(name) || log.startsWith(`[${name}]`) || log.includes(`${name}:`)
        );

        if (foundAgent) {
            // Clean up the message if needed, or just show it as is.
            // Let's try to remove the name prefix for cleaner chat if it's obvious
            let cleanMsg = log.replace(new RegExp(`^\\[?${foundAgent}\\]?:?\\s*`), '');
            // Also remove timestamp if present [Include timestamp regex just in case] e.g. [2024-...]
            cleanMsg = cleanMsg.replace(/^\[.*?\]\s*/, '');
            return { agent: foundAgent, message: cleanMsg, isSystem: false };
        }

        // Clean up System messages (timestamps, "System: ")
        let systemMsg = log.replace(/^\[.*?\]\s*/, '').replace(/^System:\s*/, '');
        return { agent: 'System', message: systemMsg, isSystem: true };
    };

    useEffect(() => {
        // Initial system message
        setMessages([{ agent: 'System', message: `Target: ${userIdea}`, isSystem: true }]);

        const eventSource = connectToStream(projectId);

        eventSource.addEventListener('log', (e) => {
            const rawMessage = e.data;
            setStatus(rawMessage);

            const parsed = parseLog(rawMessage);
            setMessages(prev => [...prev, parsed]);

            if (rawMessage.includes("Project Ready")) {
                setIsComplete(true);
            }
        });

        eventSource.onerror = () => {
            eventSource.close();
            setMessages(prev => [...prev, { agent: 'System', message: 'Workflow connection closed.', isSystem: true }]);
        };

        return () => {
            eventSource.close();
        };
    }, [projectId, userIdea]);

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }, [messages]);

    const handleDownload = () => {
        window.open(`http://localhost:8080/api/project/${projectId}/download`, '_blank');
    };

    return (
        <div className="container" style={{ padding: 0, height: '100vh', maxWidth: '100%' }}>
            {/* Header */}
            <header style={{
                padding: '1rem 2rem',
                background: 'rgba(0,0,0,0.8)',
                borderBottom: '1px solid var(--border-color)',
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                backdropFilter: 'blur(10px)',
                zIndex: 10
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                    <h2 className="title-cursive" style={{ color: 'var(--brand-gold)', fontSize: '2rem', margin: 0 }}>
                        Nirmaan Yantra
                    </h2>
                    <span style={{ color: 'var(--text-secondary)', fontSize: '0.9rem', borderLeft: '1px solid #333', paddingLeft: '1rem' }}>
                        PROJECT ID: {projectId.split('-')[0]}
                    </span>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
                    <div style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '0.5rem',
                        color: isComplete ? 'var(--accent-green)' : 'var(--brand-gold)',
                        fontSize: '0.9rem',
                        fontWeight: 'bold',
                        textTransform: 'uppercase',
                        letterSpacing: '1px'
                    }}>
                        <div style={{
                            width: '8px',
                            height: '8px',
                            borderRadius: '50%',
                            background: isComplete ? 'var(--accent-green)' : 'var(--brand-gold)',
                            boxShadow: isComplete ? '0 0 10px var(--accent-green)' : 'none',
                            animation: isComplete ? 'none' : 'pulse-gold 2s infinite'
                        }} />
                        {isComplete ? 'ONLINE' : 'BUILDING'}
                    </div>

                    {isComplete && (
                        <button
                            onClick={handleDownload}
                            style={{
                                padding: '0.6rem 1.2rem',
                                background: 'transparent',
                                border: '1px solid var(--accent-green)',
                                color: 'var(--accent-green)',
                                borderRadius: '4px',
                                fontWeight: 'bold',
                                cursor: 'pointer',
                                transition: 'all 0.3s',
                                fontSize: '0.85rem',
                                letterSpacing: '1px',
                                textTransform: 'uppercase'
                            }}
                            onMouseOver={(e) => {
                                e.target.style.background = 'var(--accent-green)';
                                e.target.style.color = 'black';
                            }}
                            onMouseOut={(e) => {
                                e.target.style.background = 'transparent';
                                e.target.style.color = 'var(--accent-green)';
                            }}
                        >
                            Download Artifacts
                        </button>
                    )}
                </div>
            </header>

            {/* Main Content Area - Split View */}
            <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>

                {/* Chat Area */}
                <div style={{
                    flex: 1,
                    display: 'flex',
                    flexDirection: 'column',
                    background: 'var(--bg-primary)',
                    position: 'relative'
                }}>
                    <div style={{
                        flex: 1,
                        overflowY: 'auto',
                        padding: '2rem',
                        display: 'flex',
                        flexDirection: 'column'
                    }}>
                        {messages.map((msg, i) => (
                            <ChatBubble
                                key={i}
                                agent={msg.agent}
                                message={msg.message}
                                isSystem={msg.isSystem}
                            />
                        ))}
                        <div ref={messagesEndRef} />
                    </div>

                    {/* Status Bar / Input placeholder */}
                    <div style={{
                        padding: '1rem 2rem',
                        borderTop: '1px solid var(--border-color)',
                        background: 'var(--bg-secondary)',
                        color: 'var(--text-secondary)',
                        fontSize: '0.9rem',
                        fontFamily: 'var(--font-mono)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '1rem'
                    }}>
                        <span style={{ color: 'var(--brand-gold)' }}>❯</span>
                        {status}
                    </div>
                </div>

                {/* Artifacts Side Panel */}
                <ArtifactsPanel projectId={projectId} />
            </div>
        </div>
    );
};

export default Dashboard;
