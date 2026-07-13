import { createContext, useContext, useState, useEffect } from 'react';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
    const [user, setUser] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        // Restore session from localStorage on page reload
        const token = localStorage.getItem('token');
        const email = localStorage.getItem('email');
        const orgName = localStorage.getItem('orgName');
        if (token && email) {
            setUser({ token, email, orgName });
        }
        setLoading(false);
    }, []);

    const login = (authResponse) => {
        localStorage.setItem('token', authResponse.token);
        localStorage.setItem('email', authResponse.email);
        localStorage.setItem('orgName', authResponse.organizationName);
        localStorage.setItem('orgId', authResponse.organizationId);
        setUser({
            token: authResponse.token,
            email: authResponse.email,
            orgName: authResponse.organizationName,
        });
    };

    const logout = () => {
        localStorage.clear();
        setUser(null);
    };

    return (
        <AuthContext.Provider value={{ user, login, logout, loading }}>
            {children}
        </AuthContext.Provider>
    );
}

export const useAuth = () => useContext(AuthContext);