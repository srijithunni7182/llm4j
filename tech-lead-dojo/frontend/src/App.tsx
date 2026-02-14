import React, { useState } from 'react';
import type { ProjectState, TurnResult, DojoEvent, DojoOption } from './types';
import { Play, Activity, Users, MessageSquare, Heart, Shield, Star, Zap } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import StakeholderCard from './components/StakeholderCard';

const API_BASE = 'http://localhost:8080/api/simulation';

function App() {
  const [gameState, setGameState] = useState<ProjectState | null>(null);
  const [currentEvent, setCurrentEvent] = useState<DojoEvent | null>(null);
  const [feedback, setFeedback] = useState<string>('');
  const [loading, setLoading] = useState(false);

  const startGame = async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/start`, { method: 'POST' });
      const state = await res.json();
      setGameState(state);
      // Immediately fetch first turn/event
      const turnRes = await fetch(`${API_BASE}/advance`, { 
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(null) 
      });
      const turn: TurnResult = await turnRes.json();
      updateTurn(turn);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  const updateTurn = (turn: TurnResult) => {
    setGameState(turn.state);
    setCurrentEvent(turn.event);
    setFeedback(turn.feedback);
  }

  const handleOptionSelect = async (option: DojoOption) => {
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/advance`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(option),
      });
      const turn: TurnResult = await res.json();
      updateTurn(turn);
    } catch (e) {
      console.error(e);
    }
    setLoading(false);
  };

  if (!gameState) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-[url('/grid-bg.png')] bg-cover">
        <div className="glass-panel p-10 text-center max-w-2xl mx-auto neon-border">
          <h1 className="text-6xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-neon-blue to-neon-pink mb-4">
            TECH LEAD DOJO
          </h1>
          <p className="text-xl text-gray-300 mb-8">
            Can you survive the sprint? Manage stakeholders, technical debt, and team morale in this high-pressure simulation.
          </p>
          <button
            onClick={startGame}
            disabled={loading}
            className="px-8 py-4 bg-neon-blue/20 hover:bg-neon-blue/40 text-neon-blue border border-neon-blue rounded-full text-xl font-bold transition-all flex items-center gap-2 mx-auto"
          >
            {loading ? <Activity className="animate-spin" /> : <Play />}
            ENTER SIMULATION
          </button>
        </div>
      </div>
    );
  }

  if (gameState.isGameOver) {
    return (
        <div className="min-h-screen p-8 flex flex-col items-center justify-center bg-cyber-black">
            <motion.div 
                initial={{ scale: 0.9, opacity: 0 }}
                animate={{ scale: 1, opacity: 1 }}
                className="glass-panel p-12 w-full max-w-2xl text-center neon-border"
            >
                <h1 className="text-5xl font-bold text-white mb-2 uppercase tracking-tighter">Simulation Complete</h1>
                <p className="text-gray-400 mb-8 italic">Sprint Retrospective Summary</p>
                
                <div className="grid grid-cols-2 gap-6 mb-10">
                    {Object.entries(gameState.metrics).map(([key, val]) => (
                        <div key={key} className="p-4 bg-white/5 rounded-lg border border-white/5">
                            <div className="text-xs text-gray-500 uppercase mb-1">{key}</div>
                            <div className={`text-3xl font-mono font-bold ${val < 40 ? 'text-red-500' : val > 70 ? 'text-green-400' : 'text-neon-blue'}`}>
                                {val}%
                            </div>
                        </div>
                    ))}
                </div>

                <div className="text-lg text-gray-300 mb-10 leading-relaxed">
                    The project has concluded. Based on your decisions, the system integrity stands at <span className="text-neon-blue font-bold">{gameState.metrics['Quality']}%</span>. 
                    {gameState.metrics['Morale'] < 50 ? " Your team is burnt out." : " Your team is still standing."}
                </div>

                 <button
                    onClick={() => setGameState(null)}
                    className="px-8 py-3 bg-neon-pink/20 hover:bg-neon-pink/40 text-neon-pink border border-neon-pink rounded-full font-bold transition-all"
                >
                    RESTART SIMULATION
                </button>
            </motion.div>
        </div>
    )
  }

  return (
    <div className="min-h-screen flex flex-col p-6 gap-6 bg-cyber-black">
        {/* HEADER / HUD */}
        <header className="flex justify-between items-center glass-panel p-6 neon-border">
            <div>
                <h2 className="text-2xl font-bold text-neon-blue tracking-tight">{gameState.systemDefinition.name}</h2>
                <div className="text-sm text-gray-400 flex items-center gap-2">
                    <Activity size={14} className="text-neon-pink" />
                    Iteration {gameState.currentIteration} / {gameState.maxIterations}
                </div>
            </div>
            
            <div className="flex gap-8">
                <MetricBadge icon={<Zap size={14}/>} label="Tech Debt" value={gameState.metrics['TechDebt'] || 0} inverse />
                <MetricBadge icon={<Heart size={14}/>} label="Morale" value={gameState.metrics['Morale'] || 0} />
                <MetricBadge icon={<Shield size={14}/>} label="Quality" value={gameState.metrics['Quality'] || 0} />
                <MetricBadge icon={<Star size={14}/>} label="Satisfaction" value={gameState.metrics['StakeholderSatisfaction'] || 0} />
            </div>
        </header>

        <div className="flex flex-1 gap-6 overflow-hidden">
            {/* MAIN STAGE */}
            <main className="flex-[3] flex flex-col gap-6 overflow-y-auto pr-2">
                 <AnimatePresence mode="wait">
                    {currentEvent && (
                        <motion.div 
                            key={currentEvent.id}
                            initial={{ opacity: 0, x: -20 }}
                            animate={{ opacity: 1, x: 0 }}
                            exit={{ opacity: 0, x: 20 }}
                            className="glass-panel p-8 flex-1 flex flex-col relative"
                        >
                             <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-neon-blue via-neon-pink to-transparent" />
                             
                             <div className="flex items-start gap-8 mb-8">
                                {currentEvent.source && (
                                    <div className="relative shrink-0">
                                        <div className="absolute -inset-1 bg-neon-blue/20 blur rounded-2xl" />
                                        <img 
                                            src={currentEvent.source.avatarPath} 
                                            alt={currentEvent.source.name}
                                            className="w-32 h-32 rounded-2xl border-2 border-white/10 object-cover relative"
                                        />
                                        <div className="absolute -bottom-3 left-1/2 -translate-x-1/2 bg-black border border-neon-blue/50 text-[10px] px-3 py-1 rounded-full whitespace-nowrap font-bold uppercase tracking-wider text-neon-blue shadow-[0_0_10px_rgba(0,243,255,0.2)]">
                                            {currentEvent.source.currentMood || 'Neutral'}
                                        </div>
                                    </div>
                                )}
                                <div>
                                    <div className="flex items-center gap-3 mb-3">
                                        <span className="bg-neon-pink/10 text-neon-pink border border-neon-pink/30 px-3 py-1 rounded-full text-[10px] font-black uppercase tracking-[0.2em]">
                                            INCOMING INTEL
                                        </span>
                                        <span className="text-gray-500 text-xs font-mono tabular-nums opacity-50">STAMP_{new Date().getTime().toString().slice(-6)}</span>
                                    </div>
                                    <h2 className="text-4xl font-black mb-4 tracking-tight leading-tight">{currentEvent.title}</h2>
                                    <p className="text-xl text-gray-400 leading-relaxed max-w-3xl">{currentEvent.description}</p>
                                </div>
                             </div>

                             <div className="mt-auto grid gap-4 lg:grid-cols-2">
                                {currentEvent.options.map((opt, idx) => (
                                    <button
                                        key={opt.id}
                                        onClick={() => handleOptionSelect(opt)}
                                        disabled={loading}
                                        className="text-left p-6 rounded-xl bg-white/5 hover:bg-neon-blue/5 border border-white/5 hover:border-neon-blue/50 transition-all group relative overflow-hidden"
                                    >
                                        <div className="flex items-start gap-4">
                                            <span className="w-10 h-10 shrink-0 flex items-center justify-center rounded-lg bg-white/5 group-hover:bg-neon-blue text-gray-500 group-hover:text-black font-mono font-bold transition-colors">
                                                0{idx + 1}
                                            </span>
                                            <span className="text-lg text-gray-300 group-hover:text-white transition-colors">{opt.description}</span>
                                        </div>
                                        <div className="absolute bottom-0 left-0 w-full h-[2px] bg-neon-blue scale-x-0 group-hover:scale-x-100 transition-transform origin-left" />
                                    </button>
                                ))}
                             </div>
                        </motion.div>
                    )}
                 </AnimatePresence>
            </main>

            {/* SIDEBAR - STAKEHOLDERS */}
            <aside className="flex-1 glass-panel p-6 flex flex-col gap-6 overflow-hidden">
                <div className="flex items-center justify-between">
                    <h3 className="text-xs font-black text-gray-500 uppercase tracking-[0.3em] flex items-center gap-2">
                        <Users size={14} className="text-neon-blue" /> Stakeholder Roster
                    </h3>
                    <span className="text-[10px] font-mono text-gray-600">{gameState.stakeholders.length} ACTIVE</span>
                </div>
                
                <div className="flex flex-col gap-4 overflow-y-auto pr-2 custom-scrollbar">
                    {gameState.stakeholders.map(stakeholder => (
                        <StakeholderCard 
                            key={stakeholder.id} 
                            stakeholder={stakeholder} 
                        />
                    ))}
                </div>
            </aside>
        </div>
        
        <AnimatePresence>
            {feedback && (
                <motion.div 
                    initial={{ y: 100, opacity: 0 }}
                    animate={{ y: 0, opacity: 1 }}
                    exit={{ y: 100, opacity: 0 }}
                    className="fixed bottom-8 left-1/2 -translate-x-1/2 max-w-xl w-full px-6"
                >
                    <div className="bg-cyber-gray/90 backdrop-blur-xl border border-neon-pink/50 p-6 rounded-2xl shadow-2xl flex items-center gap-4 text-white">
                        <div className="w-12 h-12 rounded-full bg-neon-pink/20 flex items-center justify-center shrink-0">
                            <MessageSquare className="text-neon-pink" size={24} />
                        </div>
                        <p className="text-base font-medium leading-tight">{feedback}</p>
                        <button 
                            onClick={() => setFeedback('')}
                            className="ml-auto text-gray-500 hover:text-white"
                        >
                            <Shield size={18} />
                        </button>
                    </div>
                </motion.div>
            )}
        </AnimatePresence>
    </div>
  );
}

function MetricBadge({ label, value, icon, inverse = false }: { label: string, value: number, icon: React.ReactNode, inverse?: boolean }) {
    // 0-100 color scale logic
    const getColor = (v: number) => {
        if (inverse) {
            return v > 60 ? 'text-red-500' : v > 30 ? 'text-yellow-400' : 'text-green-400';
        }
        return v < 40 ? 'text-red-500' : v < 70 ? 'text-yellow-400' : 'text-green-400';
    };

    return (
        <div className="flex flex-col items-center gap-1 group">
            <div className="flex items-center gap-1 text-[10px] text-gray-500 uppercase font-black tracking-widest group-hover:text-neon-blue transition-colors">
                {icon} {label}
            </div>
            <div className={`text-2xl font-mono font-black ${getColor(value)}`}>
                {value}<span className="text-xs opacity-50 ml-0.5">%</span>
            </div>
        </div>
    )
}

export default App;

