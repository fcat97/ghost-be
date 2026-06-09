import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useState, useEffect } from 'react';
import { useWebSocket } from '../hooks/useWebSocket';
export default function Traffic() {
    const { messages, status } = useWebSocket('/ws/traffic');
    const [selectedApp, setSelectedApp] = useState('');
    const [apps, setApps] = useState([]);
    const [autoScroll, setAutoScroll] = useState(true);
    const [expandedId, setExpandedId] = useState(null);
    useEffect(() => {
        const fetchApps = async () => {
            try {
                const response = await fetch('/api/apps');
                if (response.ok) {
                    const data = await response.json();
                    setApps(Array.isArray(data) ? data : []);
                }
            }
            catch (err) {
                console.error('Failed to fetch apps:', err);
            }
        };
        fetchApps();
        const interval = setInterval(fetchApps, 5000);
        return () => clearInterval(interval);
    }, []);
    const filteredMessages = selectedApp
        ? messages.filter((m) => m.app_package === selectedApp)
        : messages;
    const getStatusColor = (status) => {
        if (status < 300)
            return 'text-green-600';
        if (status < 400)
            return 'text-blue-600';
        if (status < 500)
            return 'text-yellow-600';
        return 'text-red-600';
    };
    const getMethodColor = (method) => {
        const colors = {
            GET: 'bg-blue-100 text-blue-800',
            POST: 'bg-green-100 text-green-800',
            PUT: 'bg-yellow-100 text-yellow-800',
            DELETE: 'bg-red-100 text-red-800',
            PATCH: 'bg-purple-100 text-purple-800',
        };
        return colors[method] || 'bg-gray-100 text-gray-800';
    };
    return (_jsxs("div", { className: "py-8", children: [_jsxs("div", { className: "mb-6 flex gap-4 items-center", children: [_jsxs("div", { className: "flex-1", children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-2", children: "Filter by App" }), _jsxs("select", { value: selectedApp, onChange: (e) => setSelectedApp(e.target.value), className: "block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500", children: [_jsx("option", { value: "", children: "All Apps" }), apps.map((app) => (_jsxs("option", { value: app.package, children: [app.package, " (", app.request_count, ")"] }, app.package)))] })] }), _jsx("div", { className: "flex items-center gap-2", children: _jsxs("label", { className: "flex items-center gap-2", children: [_jsx("input", { type: "checkbox", checked: autoScroll, onChange: (e) => setAutoScroll(e.target.checked), className: "rounded" }), _jsx("span", { className: "text-sm text-gray-700", children: "Auto-scroll" })] }) }), _jsxs("div", { className: "text-sm", children: ["Status:", ' ', _jsx("span", { className: `font-medium ${status === 'connected'
                                    ? 'text-green-600'
                                    : status === 'disconnected'
                                        ? 'text-red-600'
                                        : 'text-yellow-600'}`, children: status })] })] }), _jsx("div", { className: "space-y-2", children: filteredMessages.length === 0 ? (_jsx("div", { className: "text-center py-8 text-gray-500", children: status === 'connected'
                        ? 'Waiting for traffic...'
                        : 'Not connected to WebSocket' })) : (filteredMessages.map((msg, idx) => {
                    const entryId = `${msg.timestamp}-${idx}`;
                    const isExpanded = expandedId === entryId;
                    return (_jsxs("div", { className: "bg-white border border-gray-200 rounded-lg overflow-hidden hover:shadow-md transition-shadow", children: [_jsx("button", { onClick: () => setExpandedId(isExpanded ? null : entryId), className: "w-full px-4 py-3 text-left hover:bg-gray-50 transition-colors", children: _jsxs("div", { className: "flex items-center gap-4", children: [_jsx("span", { className: `inline-block px-2 py-1 rounded text-xs font-medium ${getMethodColor(msg.method)}`, children: msg.method }), _jsx("span", { className: "flex-1 font-mono text-sm truncate", children: msg.url }), _jsx("span", { className: `text-sm font-medium ${getStatusColor(msg.response_status)}`, children: msg.response_status }), _jsxs("span", { className: "text-sm text-gray-600 w-20 text-right", children: [msg.latency_ms, "ms"] }), msg.mocked && (_jsx("span", { className: "inline-block px-2 py-1 bg-purple-100 text-purple-800 rounded text-xs font-medium", children: "Mocked" })), _jsx("span", { className: "text-xs text-gray-500 w-32 text-right", children: msg.app_package })] }) }), isExpanded && (_jsx("div", { className: "px-4 py-3 bg-gray-50 border-t border-gray-200", children: _jsxs("div", { className: "font-mono text-xs space-y-2", children: [_jsxs("div", { children: [_jsx("span", { className: "font-medium", children: "URL:" }), _jsx("div", { className: "break-all text-gray-700", children: msg.url })] }), _jsxs("div", { children: [_jsx("span", { className: "font-medium", children: "App:" }), _jsx("span", { className: "ml-2", children: msg.app_package })] }), _jsxs("div", { children: [_jsx("span", { className: "font-medium", children: "Time:" }), _jsx("span", { className: "ml-2", children: new Date(msg.timestamp).toLocaleString() })] })] }) }))] }, entryId));
                })) })] }));
}
