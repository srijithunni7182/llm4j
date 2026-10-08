import { useEffect, useState, useRef } from 'react';
import { connectToStream } from '../services/api';
import ChatBubble from './ChatBubble';
import ArtifactsPanel from './ArtifactsPanel';

const AGENT_NAMES = ['Aditi', 'Rishi', 'Vihaan', 'Dhruv', 'Drishti', 'Vishnu'];

const Dashboard = ({ projectId, userIdea }) => {
    const [messages, setMessages] = useState([]);
    const [status, setStatus] = useState('Initializing Project...');
    const [isComplete, setIsComplete] = useState(false);
    const [showTeamModal, setShowTeamModal] = useState(false);
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

                    <button
                        onClick={() => setShowTeamModal(true)}
                        style={{
                            padding: '0.6rem 1.2rem',
                            background: isComplete ? 'var(--accent-green)' : 'var(--brand-gold)',
                            border: 'none',
                            color: 'black',
                            borderRadius: '4px',
                            fontWeight: 'bold',
                            cursor: 'pointer',
                            transition: 'all 0.3s',
                            fontSize: '0.85rem',
                            letterSpacing: '1px',
                            textTransform: 'uppercase',
                            boxShadow: isComplete ? '0 0 15px rgba(0, 255, 157, 0.4)' : '0 0 10px rgba(255, 215, 0, 0.3)'
                        }}
                        onMouseOver={(e) => e.target.style.transform = 'translateY(-2px)'}
                        onMouseOut={(e) => e.target.style.transform = 'translateY(0)'}
                    >
                        {isComplete ? 'View Team & Download' : 'Meet the Team'}
                    </button>
                </div>
            </header>

            {/* Team Modal */}
            {showTeamModal && (
                <div style={{
                    position: 'fixed',
                    top: 0,
                    left: 0,
                    width: '100%',
                    height: '100%',
                    background: 'rgba(0,0,0,0.85)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    zIndex: 1000,
                    backdropFilter: 'blur(5px)'
                }} onClick={() => setShowTeamModal(false)}>
                    <div style={{
                        background: 'var(--bg-secondary)',
                        padding: '0',
                        borderRadius: '12px',
                        border: '1px solid var(--brand-gold)',
                        maxWidth: '800px',
                        width: '90%',
                        position: 'relative',
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        boxShadow: '0 0 40px rgba(0,0,0,0.7)',
                        overflow: 'hidden'
                    }} onClick={e => e.stopPropagation()}>
                        <button
                            onClick={() => setShowTeamModal(false)}
                            style={{
                                position: 'absolute',
                                top: '1rem',
                                right: '1rem',
                                background: 'rgba(0,0,0,0.5)',
                                border: 'none',
                                color: 'white',
                                width: '32px',
                                height: '32px',
                                borderRadius: '50%',
                                fontSize: '1.2rem',
                                cursor: 'pointer',
                                zIndex: 10,
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center'
                            }}
                        >×</button>

                        {/* Banner Image */}
                        <div style={{
                            width: '100%',
                            height: '300px',
                            overflow: 'hidden',
                            borderBottom: '1px solid var(--brand-gold)'
                        }}>
                            <img
                                src="/nirmaan_team.png"
                                alt="Nirmaan Yantra Team"
                                style={{
                                    width: '100%',
                                    height: '100%',
                                    objectFit: 'cover',
                                    objectPosition: 'center 20%'
                                }}
                            />
                        </div>

                        {/* Content */}
                        <div style={{
                            padding: '2rem',
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            gap: '1.5rem',
                            width: '100%'
                        }}>
                            <h2 className="title-cursive" style={{ color: 'var(--brand-gold)', fontSize: '2rem', margin: 0, textAlign: 'center' }}>
                                Project Nirmaan Team
                            </h2>

                            <p style={{ color: 'var(--text-secondary)', textAlign: 'center', maxWidth: '600px', lineHeight: '1.6' }}>
                                The autonomous agents of Nirmaan Yantra have successfully collaborated to bring your idea to life.
                            </p>

                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem', marginTop: '0.5rem', width: '100%' }}>

                                {isComplete ? (
                                    <button
                                        onClick={handleDownload}
                                        style={{
                                            padding: '0.8rem 2.5rem',
                                            background: 'var(--accent-green)',
                                            color: 'black',
                                            border: 'none',
                                            borderRadius: '4px',
                                            fontWeight: 'bold',
                                            textTransform: 'uppercase',
                                            letterSpacing: '1px',
                                            fontSize: '1rem',
                                            cursor: 'pointer',
                                            boxShadow: '0 0 15px rgba(0, 255, 157, 0.3)',
                                            width: '100%',
                                            maxWidth: '300px'
                                        }}
                                        onMouseOver={(e) => {
                                            e.target.style.transform = 'translateY(-2px)';
                                            e.target.style.boxShadow = '0 0 25px rgba(0, 255, 157, 0.5)';
                                        }}
                                        onMouseOut={(e) => {
                                            e.target.style.transform = 'translateY(0)';
                                            e.target.style.boxShadow = '0 0 15px rgba(0, 255, 157, 0.3)';
                                        }}
                                    >
                                        Download Final Artifacts (ZIP)
                                    </button>
                                ) : (
                                    <div style={{
                                        padding: '0.8rem 2rem',
                                        border: '1px dashed var(--text-secondary)',
                                        color: 'var(--text-secondary)',
                                        borderRadius: '4px',
                                        fontSize: '0.9rem',
                                        background: 'rgba(0,0,0,0.2)'
                                    }}>
                                        Build in Progress... Artifacts locked.
                                    </div>
                                )}

                                <a
                                    href="/nirmaan_team.png"
                                    download="Nirmaan_Team_Photo.png"
                                    style={{
                                        color: 'var(--brand-gold)',
                                        textDecoration: 'none',
                                        fontSize: '0.8rem',
                                        marginTop: '0.5rem',
                                        opacity: 0.8
                                    }}
                                    onMouseOver={(e) => e.target.style.opacity = 1}
                                    onMouseOut={(e) => e.target.style.opacity = 0.8}
                                >
                                    Download Team Photo
                                </a>
                            </div>
                        </div>
                    </div>
                </div>
            )}

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
