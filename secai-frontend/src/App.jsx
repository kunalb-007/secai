// src/App.jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider }            from './context/AuthContext';
import PrivateRoute                from './components/PrivateRoute';
import LoginPage                   from './pages/LoginPage';
import RegisterPage                from './pages/RegisterPage';
import DashboardPage               from './pages/DashboardPage';
import DocumentUploadPage          from './pages/DocumentUploadPage';
import DocumentListPage            from './pages/DocumentListPage';
import DocumentDetailPage          from './pages/DocumentDetailPage';
import QuestionnaireUploadPage     from './pages/QuestionnaireUploadPage';
import QuestionnaireListPage       from './pages/QuestionnaireListPage';
import QuestionnaireDetailPage     from './pages/QuestionnaireDetailPage';
import QuestionnaireReviewPage     from './pages/QuestionnaireReviewPage';

export default function App() {
  return (
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login"    element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            <Route path="/dashboard"        element={<PrivateRoute><DashboardPage /></PrivateRoute>} />
            <Route path="/documents/upload" element={<PrivateRoute><DocumentUploadPage /></PrivateRoute>} />
            <Route path="/documents/:id"    element={<PrivateRoute><DocumentDetailPage /></PrivateRoute>} />
            <Route path="/documents"        element={<PrivateRoute><DocumentListPage /></PrivateRoute>} />

            {/* Order matters: /upload and /:id/review before /:id */}
            <Route path="/questionnaires/upload"
                   element={<PrivateRoute><QuestionnaireUploadPage /></PrivateRoute>} />
            <Route path="/questionnaires/:id/review"
                   element={<PrivateRoute><QuestionnaireReviewPage /></PrivateRoute>} />
            <Route path="/questionnaires/:id"
                   element={<PrivateRoute><QuestionnaireDetailPage /></PrivateRoute>} />
            <Route path="/questionnaires"
                   element={<PrivateRoute><QuestionnaireListPage /></PrivateRoute>} />

            <Route path="/" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
  );
}