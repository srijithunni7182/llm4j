import { useState } from 'react';
import Hero from './components/Hero';
import Dashboard from './components/Dashboard';

function App() {
  const [projectId, setProjectId] = useState(null);
  const [userIdea, setUserIdea] = useState('');

  const handleStart = (id, idea) => {
    setProjectId(id);
    setUserIdea(idea);
  };

  return (
    <div className="app">
      {!projectId ? (
        <Hero onStart={handleStart} />
      ) : (
        <Dashboard projectId={projectId} userIdea={userIdea} />
      )}
    </div>
  );
}

export default App;
