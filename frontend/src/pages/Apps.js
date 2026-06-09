import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
export default function Apps() {
    const [apps, setApps] = useState([]);
    const [loading, setLoading] = useState(true);
    const navigate = useNavigate();
    useEffect(() => {
        const fetchApps = async () => {
            try {
                setLoading(true);
                const response = await fetch('/api/apps');
                if (response.ok) {
                    const data = await response.json();
                    setApps(Array.isArray(data) ? data : []);
                }
            }
            catch (err) {
                console.error('Failed to fetch apps:', err);
            }
            finally {
                setLoading(false);
            }
        };
        fetchApps();
        const interval = setInterval(fetchApps, 5000);
        return () => clearInterval(interval);
    }, []);
    if (loading) {
        return _jsx("div", { className: "py-8 text-center text-gray-500", children: "Loading apps..." });
    }
    return (_jsxs("div", { className: "py-8", children: [_jsx("h2", { className: "text-2xl font-bold text-gray-900 mb-6", children: "Intercepted Apps" }), apps.length === 0 ? (_jsx("div", { className: "text-center py-12 text-gray-500", children: "No apps detected yet. Traffic will appear here." })) : (_jsx("div", { className: "grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4", children: apps.map((app) => (_jsxs("button", { onClick: () => navigate('/?app=' + encodeURIComponent(app.package)), className: "p-4 bg-white border border-gray-200 rounded-lg hover:shadow-lg hover:border-blue-300 transition-all text-left", children: [_jsx("div", { className: "text-sm font-mono text-gray-700 truncate mb-2", children: app.package }), _jsxs("div", { className: "flex items-center justify-between", children: [_jsx("span", { className: "text-xs text-gray-500", children: "Requests:" }), _jsx("span", { className: "inline-block px-3 py-1 bg-blue-100 text-blue-700 rounded-full text-sm font-medium", children: app.request_count })] })] }, app.package))) }))] }));
}
