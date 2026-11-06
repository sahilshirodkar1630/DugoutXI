import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import '@fontsource-variable/inter';
import './index.css';
import { chooseMode, savedMode } from './lib/theme';

// index.html already set the saved Day/Dark choice before the page drew; this also matches the phone's browser bar.
const saved = savedMode();
if (saved) chooseMode(saved);

const container = document.getElementById('root');
if (!container) {
  throw new Error('index.html is missing the #root element');
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
