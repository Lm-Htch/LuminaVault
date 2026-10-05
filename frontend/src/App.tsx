import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth/AuthContext'
import { AppearanceProvider } from './appearance/AppearanceContext'
import { ConfirmProvider } from './components/ConfirmProvider'
import { ToastProvider } from './components/ToastProvider'
import { ProtectedRoute } from './components/ProtectedRoute'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { GalleryPage } from './pages/GalleryPage'
import { AssetDetailPage } from './pages/AssetDetailPage'
import { TrashPage } from './pages/TrashPage'
import { AgentSearchPage } from './pages/AgentSearchPage'
import { CharactersPage } from './pages/CharactersPage'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <AppearanceProvider>
            <ConfirmProvider>
              <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route element={<ProtectedRoute />}>
            <Route element={<Layout />}>
              <Route index element={<GalleryPage />} />
              <Route path="/assets/:id" element={<AssetDetailPage />} />
              <Route path="/agent" element={<AgentSearchPage />} />
              <Route path="/characters" element={<CharactersPage />} />
              <Route path="/trash" element={<TrashPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </ConfirmProvider>
          </AppearanceProvider>
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
