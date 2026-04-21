import { useState } from 'react';
import { startProject } from '../services/api';

const Hero = ({ onStart }) => {
    const [idea, setIdea] = useState('');
    const [loading, setLoading] = useState(false);

    const handleSubmit = async (e) => {
        e.preventDefault();
        if (!idea.trim()) return;

        setLoading(true);
        try {
            const data = await startProject(idea);
            onStart(data.projectId, idea);
        } catch (error) {
            console.error(error);
            alert('Failed to launch Nirmaan Yantra. Check backend.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="hero-container" style={{
            height: '100vh',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'center',
            alignItems: 'center',
            textAlign: 'center',
            background: 'radial-gradient(circle at center, #1a0f00 0%, #000000 100%)'
        }}>
            <h1 className="glow-text title-cursive" style={{
                fontSize: '8rem',
                marginBottom: '0.5rem',
                color: 'var(--brand-gold)',
                lineHeight: 1.2
            }}>
                Nirmaan Yantra
            </h1>
            <p style={{
                fontSize: '1.2rem',
                color: 'var(--text-secondary)',
                marginBottom: '4rem',
                maxWidth: '600px',
                letterSpacing: '1px'
            }}>
                THE AUTONOMOUS SOFTWARE FOUNDRY
            </p>

            <form onSubmit={handleSubmit} style={{ width: '100%', maxWidth: '700px', display: 'flex', flexDirection: 'column', gap: '1.5rem', position: 'relative' }}>
                <div style={{ position: 'relative', width: '100%' }}>
                    <textarea
                        value={idea}
                        onChange={(e) => setIdea(e.target.value)}
                        placeholder="Manifest your vision..."
                        style={{
                            width: '100%',
                            height: '180px',
                            background: 'rgba(255, 255, 255, 0.03)',
                            border: '1px solid var(--border-color)',
                            borderRadius: '2px',
                            padding: '1.5rem',
                            color: 'var(--text-primary)',
                            fontSize: '1.5rem',
                            fontFamily: 'var(--font-body)',
                            resize: 'none',
                            outline: 'none',
                            transition: 'all 0.3s',
                            boxShadow: '0 4px 30px rgba(0, 0, 0, 0.5)',
                            backdropFilter: 'blur(5px)'
                        }}
                        onFocus={(e) => e.target.style.borderColor = 'var(--brand-gold)'}
                        onBlur={(e) => e.target.style.borderColor = 'var(--border-color)'}
                    />
                </div>

                <button
                    type="submit"
                    disabled={loading}
                    style={{
                        background: 'linear-gradient(45deg, var(--brand-red-dim), var(--brand-red))',
                        color: 'white',
                        padding: '1.2rem',
                        borderRadius: '2px',
                        fontSize: '1.1rem',
                        fontWeight: '600',
                        letterSpacing: '2px',
                        opacity: loading ? 0.7 : 1,
                        transition: 'all 0.3s',
                        textTransform: 'uppercase',
                        boxShadow: '0 4px 15px rgba(220, 38, 38, 0.3)',
                        border: '1px solid rgba(255,255,255,0.1)'
                    }}
                    onMouseOver={(e) => !loading && (e.target.style.transform = 'translateY(-2px)')}
                    onMouseOut={(e) => !loading && (e.target.style.transform = 'translateY(0)')}
                >
                    {loading ? 'INITIALIZING PROTOCOLS...' : 'IGNITE THE ENGINE'}
                </button>
            </form>
        </div>
    );
};

export default Hero;
