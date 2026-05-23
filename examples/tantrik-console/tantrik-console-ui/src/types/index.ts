export interface FileDescriptor {
  path: string;
  name: string;
  lastModified: string; // ISO-8601
}

export interface RunSummary {
  runId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  startedAt: string;
  completedAt?: string;
  workflowName: string;
  error?: string;
}

export interface RunEvent {
  type: string;
  message: string;
  executionTier: 'LOCAL' | 'CLOUD' | 'SYSTEM';
  timestamp: string;
  metadata: Record<string, unknown>;
}

export interface TokenBreakdown {
  totalInputTokens: number;
  agentBreakdown: AgentTokenStat[];
  runDurationMs: number;
}

export interface AgentTokenStat {
  agentName: string;
  inputTokens: number;
  squeezedCount: number;
  avgCompressionRatio: number;
}

export interface EngramNode {
  id: string;
  content: string;
  tier: 'WORKING' | 'EPISODIC' | 'SEMANTIC';
  importance: number;
  topicKey: string;
}

export interface ChatMessage {
  id: string;
  prompt: string;
  script?: string;
  workflowName?: string;
  error?: string;
  timestamp: string;
}
