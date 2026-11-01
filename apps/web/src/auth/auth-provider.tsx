import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';

type AuthContextValue = {
  user: User | null;
  loading: boolean;
  error: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

const issuer = import.meta.env.VITE_OIDC_ISSUER ?? 'http://localhost:8090/realms/parcelflow';
const appReturnUri = `${window.location.origin}${window.location.pathname}`;
const manager = new UserManager({
  authority: issuer,
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'parcelflow-web',
  redirect_uri: appReturnUri,
  post_logout_redirect_uri: appReturnUri,
  response_type: 'code',
  scope: 'openid profile parcelflow-api-audience',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
  monitorSession: false,
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    const initialize = async () => {
      try {
        const callbackUrl = new URL(window.location.href);
        const isOidcCallback = callbackUrl.searchParams.has('state')
          && (callbackUrl.searchParams.has('code') || callbackUrl.searchParams.has('error'));
        if (isOidcCallback) {
          const signedIn = await manager.signinRedirectCallback();
          if (active) setUser(signedIn.expired ? null : signedIn);
          window.history.replaceState({}, document.title, `${callbackUrl.pathname}${callbackUrl.hash}`);
        } else {
          const existing = await manager.getUser();
          if (active) setUser(existing && !existing.expired ? existing : null);
        }
      } catch {
        if (active) setError('Sign in could not be completed. Please try again.');
      } finally {
        if (active) setLoading(false);
      }
    };
    void initialize();
    return () => { active = false; };
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    error,
    signIn: () => manager.signinRedirect(),
    signOut: () => manager.signoutRedirect(),
  }), [user, loading, error]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
