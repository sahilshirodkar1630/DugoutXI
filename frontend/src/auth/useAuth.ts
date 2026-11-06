import { useContext } from 'react';
import { AuthContext } from './authState';

/** Throws rather than returning null, so a component used outside the provider fails loudly in development. */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside an AuthProvider');
  }
  return context;
}
