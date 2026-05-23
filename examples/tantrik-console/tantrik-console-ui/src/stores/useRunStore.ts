import { create } from 'zustand';
import type { RunSummary, RunEvent } from '../types';

interface RunState {
  activeRunId: string | null;
  runs: RunSummary[];
  events: RunEvent[];
  startRun: (runId: string) => void;
  stopRun: () => void;
  selectRun: (runId: string) => void;
  addRun: (run: RunSummary) => void;
  addEvent: (event: RunEvent) => void;
  updateRunStatus: (runId: string, status: RunSummary['status']) => void;
}

export const useRunStore = create<RunState>((set) => ({
  activeRunId: null,
  runs: [],
  events: [],

  startRun: (runId: string) =>
    set({
      activeRunId: runId,
      events: [],
    }),

  stopRun: () =>
    set({
      activeRunId: null,
    }),

  selectRun: (runId: string) =>
    set({
      activeRunId: runId,
    }),

  addRun: (run: RunSummary) =>
    set((state) => ({
      runs: [...state.runs, run],
    })),

  addEvent: (event: RunEvent) =>
    set((state) => ({
      events: [...state.events, event],
    })),

  updateRunStatus: (runId: string, status: RunSummary['status']) =>
    set((state) => ({
      runs: state.runs.map((run) =>
        run.runId === runId ? { ...run, status } : run
      ),
    })),
}));
