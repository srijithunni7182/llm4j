import { useState, useEffect } from 'react';
import { API_BASE } from '../services/api';

const ArtifactsPanel = ({ projectId }) => {
    const [artifacts, setArtifacts] = useState([]);
    const [selectedFile, setSelectedFile] = useState(null);
    const [fileContent, setFileContent] = useState('');
    const [loading, setLoading] = useState(false);

    const fetchArtifacts = async () => {
        try {
            const res = await fetch(`${API_BASE}/${projectId}/artifacts`);
            if (res.ok) {
                const data = await res.json();
                setArtifacts(data);
            }
        } catch (err) {
            console.error("Failed to fetch artifacts", err);
        }
    };

    const fetchContent = async (filename) => {
        setLoading(true);
        try {
            const res = await fetch(`${API_BASE}/${projectId}/artifacts/${filename}`);
            if (res.ok) {
                const text = await res.text();
                setFileContent(text);
                setSelectedFile(filename);
            }
        } catch (err) {
            console.error("Failed to fetch file content", err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        // Poll for artifacts every 5 seconds
        fetchArtifacts();
        const interval = setInterval(fetchArtifacts, 5000);
        return () => clearInterval(interval);
    }, [projectId]);

    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            background: 'var(--bg-secondary)',
            borderLeft: '1px solid var(--border-color)',
            width: '350px',
            minWidth: '300px'
        }}>
            <div style={{
                padding: '1rem',
                borderBottom: '1px solid var(--border-color)',
                color: 'var(--brand-gold)',
                fontFamily: 'var(--font-heading)',
                fontSize: '1.8rem',
                textAlign: 'center',
                letterSpacing: '1px'
            }}>
                Artifacts
            </div>

            <div style={{ flex: 1, overflowY: 'auto', padding: '1rem' }}>
                {artifacts.length === 0 ? (
                    <div style={{ color: 'var(--text-secondary)', textAlign: 'center', marginTop: '2rem', fontStyle: 'italic' }}>
                        Waiting for output...
                    </div>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                        {artifacts.map((file) => (
                            <div
                                key={file}
                                onClick={() => fetchContent(file)}
                                style={{
                                    padding: '0.8rem',
                                    borderRadius: '4px',
                                    background: selectedFile === file ? 'rgba(255, 215, 0, 0.1)' : 'rgba(255, 255, 255, 0.03)',
                                    border: selectedFile === file ? '1px solid var(--brand-gold)' : '1px solid transparent',
                                    color: selectedFile === file ? 'var(--brand-gold)' : 'var(--text-primary)',
                                    cursor: 'pointer',
                                    fontSize: '0.9rem',
                                    transition: 'all 0.2s',
                                    fontFamily: 'var(--font-mono)'
                                }}
                            >
                                📄 {file}
                            </div>
                        ))}
                    </div>
                )}
            </div>

            {/* Simple Modal overlay for content viewing if selected */}
            {selectedFile && (
                <div style={{
                    position: 'fixed',
                    top: 0,
                    left: 0,
                    right: 0,
                    bottom: 0,
                    background: 'rgba(0,0,0,0.8)',
                    zIndex: 1000,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    backdropFilter: 'blur(5px)'
                }} onClick={() => setSelectedFile(null)}>
                    <div style={{
                        width: '80%',
                        height: '80%',
                        background: 'var(--bg-primary)',
                        border: '1px solid var(--border-gold)',
                        borderRadius: '8px',
                        display: 'flex',
                        flexDirection: 'column',
                        overflow: 'hidden'
                    }} onClick={(e) => e.stopPropagation()}>
                        <div style={{
                            padding: '1rem',
                            borderBottom: '1px solid var(--border-color)',
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            background: 'var(--bg-secondary)'
                        }}>
                            <span style={{ color: 'var(--brand-gold)', fontWeight: 'bold' }}>{selectedFile}</span>
                            <button onClick={() => setSelectedFile(null)} style={{ color: 'var(--text-secondary)', background: 'transparent', fontSize: '1.2rem' }}>✕</button>
                        </div>
                        <div style={{
                            flex: 1,
                            overflow: 'auto',
                            padding: '1.5rem',
                            fontFamily: 'var(--font-mono)',
                            fontSize: '0.9rem',
                            color: '#e2e2e2',
                            whiteSpace: 'pre-wrap'
                        }}>
                            {loading ? 'Loading...' : fileContent}
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default ArtifactsPanel;
