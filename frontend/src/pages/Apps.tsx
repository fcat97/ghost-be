import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'

interface AppInfo {
  package: string
  request_count: number
}

export default function Apps() {
  const [apps, setApps] = useState<AppInfo[]>([])
  const [loading, setLoading] = useState(true)
  const navigate = useNavigate()

  useEffect(() => {
    const fetchApps = async () => {
      try {
        setLoading(true)
        const response = await fetch('/api/apps')
        if (response.ok) {
          const data = await response.json()
          setApps(Array.isArray(data) ? data : [])
        }
      } catch (err) {
        console.error('Failed to fetch apps:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchApps()
    const interval = setInterval(fetchApps, 5000)
    return () => clearInterval(interval)
  }, [])

  if (loading) {
    return <div className="py-8 text-center text-gray-500">Loading apps...</div>
  }

  return (
    <div className="py-8">
      <h2 className="text-2xl font-bold text-gray-900 mb-6">Intercepted Apps</h2>

      {apps.length === 0 ? (
        <div className="text-center py-12 text-gray-500">
          No apps detected yet. Traffic will appear here.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {apps.map((app) => (
            <button
              key={app.package}
              onClick={() => navigate('/?app=' + encodeURIComponent(app.package))}
              className="p-4 bg-white border border-gray-200 rounded-lg hover:shadow-lg hover:border-blue-300 transition-all text-left"
            >
              <div className="text-sm font-mono text-gray-700 truncate mb-2">
                {app.package}
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-500">Requests:</span>
                <span className="inline-block px-3 py-1 bg-blue-100 text-blue-700 rounded-full text-sm font-medium">
                  {app.request_count}
                </span>
              </div>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
