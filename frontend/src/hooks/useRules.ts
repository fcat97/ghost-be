import { useState, useEffect } from 'react'

export interface Rule {
  id: number
  app_package: string
  url_pattern: string
  method: string
  response_status: number
  response_body?: string | null
  enabled: boolean
  created_at: string
}

export const useRules = () => {
  const [rules, setRules] = useState<Rule[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchRules = async () => {
    try {
      setLoading(true)
      const response = await fetch('/api/rules')
      if (!response.ok) throw new Error('Failed to fetch rules')
      const data = await response.json()
      setRules(Array.isArray(data) ? data : [])
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }

  const createRule = async (rule: Omit<Rule, 'id' | 'created_at'>) => {
    try {
      const response = await fetch('/api/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(rule),
      })
      if (!response.ok) throw new Error('Failed to create rule')
      const newRule = await response.json()
      setRules([newRule, ...rules])
      return newRule
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
      throw err
    }
  }

  const updateRule = async (id: number, rule: Partial<Rule>) => {
    try {
      const response = await fetch(`/api/rules/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(rule),
      })
      if (!response.ok) throw new Error('Failed to update rule')
      const updated = await response.json()
      setRules(rules.map((r) => (r.id === id ? updated : r)))
      return updated
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
      throw err
    }
  }

  const deleteRule = async (id: number) => {
    try {
      const response = await fetch(`/api/rules/${id}`, {
        method: 'DELETE',
      })
      if (!response.ok) throw new Error('Failed to delete rule')
      setRules(rules.filter((r) => r.id !== id))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
      throw err
    }
  }

  const toggleRule = async (id: number) => {
    try {
      const response = await fetch(`/api/rules/${id}/toggle`, {
        method: 'PATCH',
      })
      if (!response.ok) throw new Error('Failed to toggle rule')
      const updated = await response.json()
      setRules(rules.map((r) => (r.id === id ? updated : r)))
      return updated
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
      throw err
    }
  }

  useEffect(() => {
    fetchRules()
  }, [])

  return { rules, loading, error, createRule, updateRule, deleteRule, toggleRule, refetch: fetchRules }
}
