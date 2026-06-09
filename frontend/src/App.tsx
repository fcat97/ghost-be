import { BrowserRouter, Routes, Route, Link } from 'react-router-dom'
import Traffic from './pages/Traffic'
import Rules from './pages/Rules'
import Apps from './pages/Apps'
import Settings from './pages/Settings'

export default function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-gray-50">
        <nav className="bg-white shadow-sm">
          <div className="max-w-7xl mx-auto px-4 py-4 flex items-center justify-between">
            <h1 className="text-2xl font-bold text-gray-900">GhostBe</h1>
            <div className="flex gap-4">
              <Link to="/" className="text-gray-700 hover:text-gray-900">Traffic</Link>
              <Link to="/rules" className="text-gray-700 hover:text-gray-900">Rules</Link>
              <Link to="/apps" className="text-gray-700 hover:text-gray-900">Apps</Link>
              <Link to="/settings" className="text-gray-700 hover:text-gray-900">Settings</Link>
            </div>
          </div>
        </nav>
        <main className="max-w-7xl mx-auto">
          <Routes>
            <Route path="/" element={<Traffic />} />
            <Route path="/rules" element={<Rules />} />
            <Route path="/apps" element={<Apps />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
