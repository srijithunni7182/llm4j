import React from 'react';
import Aditi from '../assets/personas/Aditi.png';
import Dhruv from '../assets/personas/Dhruv.png';
import Drishti from '../assets/personas/Drishti.png';
import Rishi from '../assets/personas/Rishi.png';
import Vihaan from '../assets/personas/Vihaan.png';
import Vishnu from '../assets/personas/Vishnu.png';

const PERSONAS = {
    Aditi,
    Dhruv,
    Drishti,
    Rishi,
    Vihaan,
    Vishnu
};

const ChatBubble = ({ agent, message, isSystem }) => {
    const persona = PERSONAS[agent];

    if (isSystem) {
        return (
            <div style={{
                display: 'flex',
                justifyContent: 'center',
                padding: '1rem',
                color: 'var(--text-secondary)',
                fontFamily: 'var(--font-mono)',
                fontSize: '0.9rem',
                borderBottom: '1px solid rgba(255,255,255,0.05)',
                background: 'rgba(255, 255, 255, 0.02)'
            }}>
                &gt; {message}
            </div>
        );
    }

    return (
        <div style={{
            display: 'flex',
            gap: '1.5rem',
            padding: '1.5rem',
            marginBottom: '1rem',
            background: 'rgba(20, 20, 20, 0.6)',
            borderLeft: '3px solid var(--brand-gold)',
            borderRadius: '0 8px 8px 0',
            animation: 'fadeIn 0.5s ease-out'
        }}>
            <div style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: '0.5rem',
                minWidth: '80px'
            }}>
                <div style={{
                    width: '60px',
                    height: '60px',
                    borderRadius: '50%',
                    overflow: 'hidden',
                    border: '2px solid var(--brand-gold)',
                    boxShadow: '0 0 15px rgba(255, 215, 0, 0.2)'
                }}>
                    <img src={persona} alt={agent} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                </div>
                <span style={{
                    color: 'var(--brand-gold)',
                    fontSize: '0.8rem',
                    fontWeight: '600',
                    letterSpacing: '1px'
                }}>
                    {agent.toUpperCase()}
                </span>
            </div>

            <div style={{ flex: 1 }}>
                <div style={{
                    color: 'var(--text-primary)',
                    fontSize: '1.05rem',
                    lineHeight: '1.6',
                    whiteSpace: 'pre-wrap'
                }}>
                    {message}
                </div>
            </div>
        </div>
    );
};

export default ChatBubble;
