import { useState, useEffect } from 'react'

interface Status {
  running: boolean
  port: number
  ca_fingerprint: string
}

export default function Settings() {
  const [status, setStatus] = useState<Status | null>(null)
  const [loading, setLoading] = useState(true)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const response = await fetch('/api/status')
        if (response.ok) {
          const data = await response.json()
          setStatus(data)
        }
      } catch (err) {
        console.error('Failed to fetch status:', err)
      } finally {
        setLoading(false)
      }
    }

    fetchStatus()
  }, [])

  const handleCopyFingerprint = () => {
    if (status?.ca_fingerprint) {
      navigator.clipboard.writeText(status.ca_fingerprint)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }

  const handleDownloadCA = () => {
    const link = document.createElement('a')
    link.href = '/api/ca'
    link.download = 'ca.crt'
    link.click()
  }

  if (loading) {
    return <div className="py-8 text-center text-gray-500">Loading settings...</div>
  }

  if (!status) {
    return <div className="py-8 text-center text-red-500">Failed to load settings</div>
  }

  return (
    <div className="py-8 space-y-8">
      <div>
        <h2 className="text-2xl font-bold text-gray-900 mb-6">Settings</h2>

        <div className="bg-white border border-gray-200 rounded-lg p-6 space-y-4">
          <div>
            <h3 className="text-lg font-semibold text-gray-900 mb-2">Proxy Status</h3>
            <div className="flex items-center gap-2">
              <div
                className={`w-3 h-3 rounded-full ${status.running ? 'bg-green-500' : 'bg-red-500'}`}
              />
              <span className="text-gray-700">
                {status.running ? 'Running' : 'Not Running'} on port {status.port}
              </span>
            </div>
          </div>

          <div>
            <h3 className="text-lg font-semibold text-gray-900 mb-2">CA Certificate</h3>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  CA Fingerprint (SHA256)
                </label>
                <div className="flex gap-2">
                  <code className="flex-1 px-3 py-2 bg-gray-100 border border-gray-300 rounded font-mono text-sm text-gray-700 break-all">
                    {status.ca_fingerprint}
                  </code>
                  <button
                    onClick={handleCopyFingerprint}
                    className="px-4 py-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 transition-colors"
                  >
                    {copied ? '✓ Copied' : 'Copy'}
                  </button>
                </div>
              </div>

              <button
                onClick={handleDownloadCA}
                className="w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium"
              >
                ↓ Download CA Certificate
              </button>
            </div>
          </div>
        </div>
      </div>

      <div>
        <h3 className="text-lg font-bold text-gray-900 mb-4">Android Setup Instructions</h3>
        <div className="bg-blue-50 border border-blue-200 rounded-lg p-6 space-y-4">
          <div>
            <h4 className="font-semibold text-gray-900 mb-2">1. Download CA Certificate</h4>
            <p className="text-gray-700">Click the "Download CA Certificate" button above.</p>
          </div>

          <div>
            <h4 className="font-semibold text-gray-900 mb-2">
              2. Transfer Certificate to Android Device
            </h4>
            <p className="text-gray-700">
              Use adb push or any file transfer method to copy ca.crt to your Android device.
            </p>
            <code className="block mt-2 px-3 py-2 bg-white border border-gray-300 rounded font-mono text-sm">
              adb push ca.crt /storage/emulated/0/Download/
            </code>
          </div>

          <div>
            <h4 className="font-semibold text-gray-900 mb-2">3. Install Certificate</h4>
            <ul className="list-disc list-inside text-gray-700 space-y-1">
              <li>Open Settings → Security → Install certificates from storage</li>
              <li>Select ca.crt from Downloads</li>
              <li>Name it "GhostBe" (or any name you prefer)</li>
            </ul>
          </div>

          <div>
            <h4 className="font-semibold text-gray-900 mb-2">
              4. Configure GhostBe App
            </h4>
            <ul className="list-disc list-inside text-gray-700 space-y-1">
              <li>Install the GhostBe Android app</li>
              <li>Enter your PC's IP address and proxy port (default 8877)</li>
              <li>Select which apps to intercept</li>
              <li>Tap "Connect"</li>
            </ul>
          </div>

          <div>
            <h4 className="font-semibold text-gray-900 mb-2">5. Start Testing</h4>
            <p className="text-gray-700">
              Open the app you want to test. Requests will appear in the Traffic tab.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
