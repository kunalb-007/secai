// src/App.jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import PrivateRoute from './components/PrivateRoute';
import LoginPage          from './pages/LoginPage';
import RegisterPage       from './pages/RegisterPage';
import DashboardPage      from './pages/DashboardPage';
import DocumentUploadPage from './pages/DocumentUploadPage';
import DocumentListPage   from './pages/DocumentListPage';
import DocumentDetailPage from './pages/DocumentDetailPage';

export default function App() {
  return (
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            {/* Public */}
            <Route path="/login"    element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            {/* Protected */}
            <Route path="/dashboard" element={
              <PrivateRoute><DashboardPage /></PrivateRoute>
            } />
            <Route path="/documents/upload" element={
              <PrivateRoute><DocumentUploadPage /></PrivateRoute>
            } />
            <Route path="/documents/:id" element={
              <PrivateRoute><DocumentDetailPage /></PrivateRoute>
            } />
            <Route path="/documents" element={
              <PrivateRoute><DocumentListPage /></PrivateRoute>
            } />

            {/* Root redirect */}
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
  );
}