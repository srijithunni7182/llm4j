import { create } from 'zustand';
import type { FileDescriptor } from '../types';

interface EditorState {
  currentFile: FileDescriptor | null;
  content: string;
  savedContent: string;
  isDirty: boolean;
  setContent: (content: string) => void;
  setSavedContent: (content: string) => void;
  loadFile: (file: FileDescriptor, content: string) => void;
}

export const useEditorStore = create<EditorState>((set) => ({
  currentFile: null,
  content: '',
  savedContent: '',
  isDirty: false,

  setContent: (content: string) =>
    set((state) => ({
      content,
      isDirty: content !== state.savedContent,
    })),

  setSavedContent: (savedContent: string) =>
    set((state) => ({
      savedContent,
      isDirty: state.content !== savedContent,
    })),

  loadFile: (file: FileDescriptor, content: string) =>
    set({
      currentFile: file,
      content,
      savedContent: content,
      isDirty: false,
    }),
}));
