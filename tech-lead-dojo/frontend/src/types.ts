export interface StakeholderProfile {
    id: string;
    name: string;
    role: string;
    focusArea: string;
    avatarPath: string;
    currentMood: string;
}

export interface DojoOption {
    id: string;
    description: string;
    hiddenImpact: Record<string, number>;
}

export interface DojoEvent {
    id: string;
    title: string;
    description: string;
    source: StakeholderProfile | null;
    options: DojoOption[];
}

export interface ProjectState {
    currentIteration: number;
    maxIterations: number;
    systemDefinition: {
        name: string;
        description: string;
        userTeam: { name: string; responsibility: string; techLeadName: string };
        dependencyTeams: { name: string; responsibility: string; techLeadName: string }[];
    };
    metrics: Record<string, number>;
    stakeholderStatuses: Record<string, string>;
    stakeholders: StakeholderProfile[];
    isGameOver: boolean;
}

export interface TurnResult {
    state: ProjectState;
    event: DojoEvent | null;
    feedback: string;
}
