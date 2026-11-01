import { createRoot } from 'react-dom/client';
import { AuthProvider } from './auth/auth-provider';
import { App } from './App';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <AuthProvider>
    <App />
  </AuthProvider>,
);
