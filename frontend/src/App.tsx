import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import WorkbenchPage from './pages/workbench/WorkbenchPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/workbench" element={<WorkbenchPage />} />
        <Route path="*" element={<Navigate to="/workbench" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
