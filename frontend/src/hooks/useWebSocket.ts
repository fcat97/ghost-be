import { useState, useEffect, useRef } from 'react'

export interface WebSocketMessage {
  timestamp: string
  app_package: string
  method: string
  url: string
  response_status: number
  latency_ms: number
  mocked: boolean
}

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

export const useWebSocket = (url: string) => {
  const [messages, setMessages] = useState<WebSocketMessage[]>([])
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null)
  const reconnectAttemptsRef = useRef(0)

  useEffect(() => {
    const connect = () => {
      try {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
        const wsUrl = url.startsWith('ws') ? url : `${protocol}//${window.location.host}${url}`
        
        const ws = new WebSocket(wsUrl)

        ws.onopen = () => {
          setStatus('connected')
          reconnectAttemptsRef.current = 0
        }

        ws.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            setMessages((prev) => [message, ...prev].slice(0, 1000))
          } catch (e) {
            console.error('Failed to parse message:', e)
          }
        }

        ws.onerror = () => {
          setStatus('error')
        }

        ws.onclose = () => {
          setStatus('disconnected')
          attemptReconnect()
        }

        wsRef.current = ws
      } catch (e) {
        setStatus('error')
        attemptReconnect()
      }
    }

    const attemptReconnect = () => {
      reconnectAttemptsRef.current += 1
      const delay = Math.min(1000 * Math.pow(2, reconnectAttemptsRef.current - 1), 30000)
      
      if (reconnectAttemptsRef.current < 10) {
        reconnectTimeoutRef.current = setTimeout(connect, delay)
      }
    }

    connect()

    return () => {
      if (reconnectTimeoutRef.current) clearTimeout(reconnectTimeoutRef.current)
      if (wsRef.current) wsRef.current.close()
    }
  }, [url])

  return { messages, status }
}
