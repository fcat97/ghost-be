import { useState, useEffect } from 'react'
import { useWebSocket } from '../hooks/useWebSocket'

interface TrafficEntry {
  timestamp: string
  app_package: string
  method: string
  url: string
  response_status: number
  latency_ms: number
  mocked: boolean
}

interface AppOption {
  package: string
  request_count: number
}

export default function Traffic() {
  const { messages, status } = useWebSocket('/ws/traffic')
  const [selectedApp, setSelectedApp] = useState<string>('')
  const [apps, setApps] = useState<AppOption[]>([])
  const [autoScroll, setAutoScroll] = useState(true)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  useEffect(() => {
    const fetchApps = async () => {
      try {
        const response = await fetch('/api/apps')
        if (response.ok) {
          const data = await response.json()
          setApps(Array.isArray(data) ? data : [])
        }
      } catch (err) {
        console.error('Failed to fetch apps:', err)
      }
    }

    fetchApps()
    const interval = setInterval(fetchApps, 5000)
    return () => clearInterval(interval)
  }, [])

  const filteredMessages = selectedApp
    ? messages.filter((m) => m.app_package === selectedApp)
    : messages

  const getStatusColor = (status: number) => {
    if (status < 300) return 'text-green-600'
    if (status < 400) return 'text-blue-600'
    if (status < 500) return 'text-yellow-600'
    return 'text-red-600'
  }

  const getMethodColor = (method: string) => {
    const colors: Record<string, string> = {
      GET: 'bg-blue-100 text-blue-800',
      POST: 'bg-green-100 text-green-800',
      PUT: 'bg-yellow-100 text-yellow-800',
      DELETE: 'bg-red-100 text-red-800',
      PATCH: 'bg-purple-100 text-purple-800',
    }
    return colors[method] || 'bg-gray-100 text-gray-800'
  }

  return (
    <div className="py-8">
      <div className="mb-6 flex gap-4 items-center">
        <div className="flex-1">
          <label className="block text-sm font-medium text-gray-700 mb-2">
            Filter by App
          </label>
          <select
            value={selectedApp}
            onChange={(e) => setSelectedApp(e.target.value)}
            className="block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          >
            <option value="">All Apps</option>
            {apps.map((app) => (
              <option key={app.package} value={app.package}>
                {app.package} ({app.request_count})
              </option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-2">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={autoScroll}
              onChange={(e) => setAutoScroll(e.target.checked)}
              className="rounded"
            />
            <span className="text-sm text-gray-700">Auto-scroll</span>
          </label>
        </div>

        <div className="text-sm">
          Status:{' '}
          <span
            className={`font-medium ${
              status === 'connected'
                ? 'text-green-600'
                : status === 'disconnected'
                ? 'text-red-600'
                : 'text-yellow-600'
            }`}
          >
            {status}
          </span>
        </div>
      </div>

      <div className="space-y-2">
        {filteredMessages.length === 0 ? (
          <div className="text-center py-8 text-gray-500">
            {status === 'connected'
              ? 'Waiting for traffic...'
              : 'Not connected to WebSocket'}
          </div>
        ) : (
          filteredMessages.map((msg, idx) => {
            const entryId = `${msg.timestamp}-${idx}`
            const isExpanded = expandedId === entryId

            return (
              <div
                key={entryId}
                className="bg-white border border-gray-200 rounded-lg overflow-hidden hover:shadow-md transition-shadow"
              >
                <button
                  onClick={() => setExpandedId(isExpanded ? null : entryId)}
                  className="w-full px-4 py-3 text-left hover:bg-gray-50 transition-colors"
                >
                  <div className="flex items-center gap-4">
                    <span className={`inline-block px-2 py-1 rounded text-xs font-medium ${getMethodColor(msg.method)}`}>
                      {msg.method}
                    </span>

                    <span className="flex-1 font-mono text-sm truncate">{msg.url}</span>

                    <span className={`text-sm font-medium ${getStatusColor(msg.response_status)}`}>
                      {msg.response_status}
                    </span>

                    <span className="text-sm text-gray-600 w-20 text-right">
                      {msg.latency_ms}ms
                    </span>

                    {msg.mocked && (
                      <span className="inline-block px-2 py-1 bg-purple-100 text-purple-800 rounded text-xs font-medium">
                        Mocked
                      </span>
                    )}

                    <span className="text-xs text-gray-500 w-32 text-right">
                      {msg.app_package}
                    </span>
                  </div>
                </button>

                {isExpanded && (
                  <div className="px-4 py-3 bg-gray-50 border-t border-gray-200">
                    <div className="font-mono text-xs space-y-2">
                      <div>
                        <span className="font-medium">URL:</span>
                        <div className="break-all text-gray-700">{msg.url}</div>
                      </div>
                      <div>
                        <span className="font-medium">App:</span>
                        <span className="ml-2">{msg.app_package}</span>
                      </div>
                      <div>
                        <span className="font-medium">Time:</span>
                        <span className="ml-2">{new Date(msg.timestamp).toLocaleString()}</span>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
