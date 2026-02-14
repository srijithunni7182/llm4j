import React from 'react';
import type { StakeholderProfile } from '../types';
import { User } from 'lucide-react';
import { motion } from 'framer-motion';

interface Props {
    stakeholder: StakeholderProfile;
}

const StakeholderCard: React.FC<Props> = ({ stakeholder }) => {
    // Mood color logic
    const getMoodColor = (mood: string) => {
        switch (mood?.toLowerCase()) {
            case 'happy': return 'text-green-400';
            case 'frustrated': return 'text-yellow-400';
            case 'angry': return 'text-red-500';
            default: return 'text-blue-400';
        }
    };

    return (
        <motion.div 
            whileHover={{ scale: 1.02 }}
            className="p-4 rounded-xl bg-white/5 border border-white/10 flex items-center gap-4 group"
        >
            <div className="relative">
                <img 
                    src={stakeholder.avatarPath} 
                    alt={stakeholder.name} 
                    className="w-12 h-12 rounded-lg object-cover border border-white/20"
                />
                <div className={`absolute -bottom-1 -right-1 w-3 h-3 rounded-full bg-current ${getMoodColor(stakeholder.currentMood)} shadow-[0_0_8px_currentColor]`} />
            </div>
            
            <div className="flex-1 min-w-0">
                <div className="flex justify-between items-start">
                    <h4 className="font-bold truncate text-gray-100">{stakeholder.name}</h4>
                </div>
                <div className="text-xs text-gray-500 flex items-center gap-1">
                    <User size={10} /> {stakeholder.role}
                </div>
                <div className="mt-1 text-[10px] uppercase tracking-wider text-neon-blue/80 font-bold">
                    Focus: {stakeholder.focusArea}
                </div>
            </div>

            <div className="text-right">
                 <div className={`text-xs font-bold ${getMoodColor(stakeholder.currentMood)}`}>
                    {stakeholder.currentMood || 'Neutral'}
                 </div>
            </div>
        </motion.div>
    );
};

export default StakeholderCard;
