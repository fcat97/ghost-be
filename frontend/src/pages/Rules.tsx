import { useState } from 'react'
import { useRules, Rule } from '../hooks/useRules'

export default function Rules() {
  const { rules, loading, error, createRule, updateRule, deleteRule, toggleRule } = useRules()
  const [showModal, setShowModal] = useState(false)
  const [editingRule, setEditingRule] = useState<Rule | null>(null)
  const [formData, setFormData] = useState({
    app_package: '',
    url_pattern: '',
    method: '*',
    response_status: 200,
    response_body: '',
    enabled: true,
  })

  const groupedRules = rules.reduce((acc, rule) => {
    if (!acc[rule.app_package]) {
      acc[rule.app_package] = []
    }
    acc[rule.app_package].push(rule)
    return acc
  }, {} as Record<string, Rule[]>)

  const handleOpenModal = (rule?: Rule) => {
    if (rule) {
      setEditingRule(rule)
      setFormData({
        app_package: rule.app_package,
        url_pattern: rule.url_pattern,
        method: rule.method,
        response_status: rule.response_status,
        response_body: rule.response_body || '',
        enabled: rule.enabled,
      })
    } else {
      setEditingRule(null)
      setFormData({
        app_package: '',
        url_pattern: '',
        method: '*',
        response_status: 200,
        response_body: '',
        enabled: true,
      })
    }
    setShowModal(true)
  }

  const handleCloseModal = () => {
    setShowModal(false)
    setEditingRule(null)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      if (editingRule) {
        await updateRule(editingRule.id, {
          ...formData,
          response_body: formData.response_body || null,
        })
      } else {
        await createRule({
          ...formData,
          response_body: formData.response_body || null,
        })
      }
      handleCloseModal()
    } catch (err) {
      console.error('Failed to save rule:', err)
    }
  }

  const handleDelete = async (id: number) => {
    if (confirm('Are you sure you want to delete this rule?')) {
      try {
        await deleteRule(id)
      } catch (err) {
        console.error('Failed to delete rule:', err)
      }
    }
  }

  if (loading) {
    return <div className="py-8 text-center text-gray-500">Loading rules...</div>
  }

  return (
    <div className="py-8">
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-2xl font-bold text-gray-900">Traffic Rules</h2>
        <button
          onClick={() => handleOpenModal()}
          className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
        >
          + New Rule
        </button>
      </div>

      {error && (
        <div className="mb-4 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700">
          {error}
        </div>
      )}

      {Object.keys(groupedRules).length === 0 ? (
        <div className="text-center py-12 text-gray-500">
          No rules yet. Create one to get started.
        </div>
      ) : (
        <div className="space-y-4">
          {Object.entries(groupedRules).map(([appPackage, appRules]) => (
            <div key={appPackage} className="border border-gray-200 rounded-lg overflow-hidden">
              <div className="bg-gray-50 px-4 py-3 font-semibold text-gray-900">
                {appPackage}
              </div>
              <div className="divide-y divide-gray-200">
                {appRules.map((rule) => (
                  <div key={rule.id} className="px-4 py-3 flex items-center gap-4">
                    <label className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        checked={rule.enabled}
                        onChange={() => toggleRule(rule.id)}
                        className="rounded"
                      />
                    </label>

                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-mono text-gray-900 truncate">
                        {rule.url_pattern}
                      </div>
                      <div className="text-xs text-gray-500">
                        {rule.method} → {rule.response_status}
                      </div>
                    </div>

                    <button
                      onClick={() => handleOpenModal(rule)}
                      className="px-3 py-1 text-sm bg-gray-100 text-gray-700 rounded hover:bg-gray-200 transition-colors"
                    >
                      Edit
                    </button>

                    <button
                      onClick={() => handleDelete(rule.id)}
                      className="px-3 py-1 text-sm bg-red-100 text-red-700 rounded hover:bg-red-200 transition-colors"
                    >
                      Delete
                    </button>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {showModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl max-w-md w-full mx-4 p-6">
            <h3 className="text-lg font-bold text-gray-900 mb-4">
              {editingRule ? 'Edit Rule' : 'New Rule'}
            </h3>

            <form onSubmit={handleSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  App Package
                </label>
                <input
                  type="text"
                  value={formData.app_package}
                  onChange={(e) => setFormData({ ...formData, app_package: e.target.value })}
                  placeholder="com.example.app"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  URL Pattern (glob or regex)
                </label>
                <input
                  type="text"
                  value={formData.url_pattern}
                  onChange={(e) => setFormData({ ...formData, url_pattern: e.target.value })}
                  placeholder="*.example.com/* or ^https://api\\..*"
                  className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Method
                </label>
                <select
                  value={formData.method}
                  onChange={(e) => setFormData({ ...formData, method: e.target.value })}
                  className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                >
                  <option value="*">* (All)</option>
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Response Status
                </label>
                <input
                  type="number"
                  value={formData.response_status}
                  onChange={(e) =>
                    setFormData({ ...formData, response_status: parseInt(e.target.value) })
                  }
                  className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Response Body (JSON)
                </label>
                <textarea
                  value={formData.response_body}
                  onChange={(e) => setFormData({ ...formData, response_body: e.target.value })}
                  placeholder='{"error": "Mocked response"}'
                  className="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-sm"
                  rows={4}
                />
              </div>

              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={formData.enabled}
                  onChange={(e) => setFormData({ ...formData, enabled: e.target.checked })}
                  className="rounded"
                />
                <span className="text-sm text-gray-700">Enabled</span>
              </label>

              <div className="flex gap-3 pt-4">
                <button
                  type="button"
                  onClick={handleCloseModal}
                  className="flex-1 px-4 py-2 text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 px-4 py-2 text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
                >
                  Save
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
