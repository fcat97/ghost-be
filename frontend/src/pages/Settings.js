import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useState, useEffect } from 'react';
export default function Settings() {
    const [status, setStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [copied, setCopied] = useState(false);
    useEffect(() => {
        const fetchStatus = async () => {
            try {
                const response = await fetch('/api/status');
                if (response.ok) {
                    const data = await response.json();
                    setStatus(data);
                }
            }
            catch (err) {
                console.error('Failed to fetch status:', err);
            }
            finally {
                setLoading(false);
            }
        };
        fetchStatus();
    }, []);
    const handleCopyFingerprint = () => {
        if (status?.ca_fingerprint) {
            navigator.clipboard.writeText(status.ca_fingerprint);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        }
    };
    const handleDownloadCA = () => {
        const link = document.createElement('a');
        link.href = '/api/ca';
        link.download = 'ca.crt';
        link.click();
    };
    if (loading) {
        return _jsx("div", { className: "py-8 text-center text-gray-500", children: "Loading settings..." });
    }
    if (!status) {
        return _jsx("div", { className: "py-8 text-center text-red-500", children: "Failed to load settings" });
    }
    return (_jsxs("div", { className: "py-8 space-y-8", children: [_jsxs("div", { children: [_jsx("h2", { className: "text-2xl font-bold text-gray-900 mb-6", children: "Settings" }), _jsxs("div", { className: "bg-white border border-gray-200 rounded-lg p-6 space-y-4", children: [_jsxs("div", { children: [_jsx("h3", { className: "text-lg font-semibold text-gray-900 mb-2", children: "Proxy Status" }), _jsxs("div", { className: "flex items-center gap-2", children: [_jsx("div", { className: `w-3 h-3 rounded-full ${status.running ? 'bg-green-500' : 'bg-red-500'}` }), _jsxs("span", { className: "text-gray-700", children: [status.running ? 'Running' : 'Not Running', " on port ", status.port] })] })] }), _jsxs("div", { children: [_jsx("h3", { className: "text-lg font-semibold text-gray-900 mb-2", children: "CA Certificate" }), _jsxs("div", { className: "space-y-4", children: [_jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-2", children: "CA Fingerprint (SHA256)" }), _jsxs("div", { className: "flex gap-2", children: [_jsx("code", { className: "flex-1 px-3 py-2 bg-gray-100 border border-gray-300 rounded font-mono text-sm text-gray-700 break-all", children: status.ca_fingerprint }), _jsx("button", { onClick: handleCopyFingerprint, className: "px-4 py-2 bg-gray-100 text-gray-700 rounded hover:bg-gray-200 transition-colors", children: copied ? '✓ Copied' : 'Copy' })] })] }), _jsx("button", { onClick: handleDownloadCA, className: "w-full px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium", children: "\u2193 Download CA Certificate" })] })] })] })] }), _jsxs("div", { children: [_jsx("h3", { className: "text-lg font-bold text-gray-900 mb-4", children: "Android Setup Instructions" }), _jsxs("div", { className: "bg-blue-50 border border-blue-200 rounded-lg p-6 space-y-4", children: [_jsxs("div", { children: [_jsx("h4", { className: "font-semibold text-gray-900 mb-2", children: "1. Download CA Certificate" }), _jsx("p", { className: "text-gray-700", children: "Click the \"Download CA Certificate\" button above." })] }), _jsxs("div", { children: [_jsx("h4", { className: "font-semibold text-gray-900 mb-2", children: "2. Transfer Certificate to Android Device" }), _jsx("p", { className: "text-gray-700", children: "Use adb push or any file transfer method to copy ca.crt to your Android device." }), _jsx("code", { className: "block mt-2 px-3 py-2 bg-white border border-gray-300 rounded font-mono text-sm", children: "adb push ca.crt /storage/emulated/0/Download/" })] }), _jsxs("div", { children: [_jsx("h4", { className: "font-semibold text-gray-900 mb-2", children: "3. Install Certificate" }), _jsxs("ul", { className: "list-disc list-inside text-gray-700 space-y-1", children: [_jsx("li", { children: "Open Settings \u2192 Security \u2192 Install certificates from storage" }), _jsx("li", { children: "Select ca.crt from Downloads" }), _jsx("li", { children: "Name it \"GhostBe\" (or any name you prefer)" })] })] }), _jsxs("div", { children: [_jsx("h4", { className: "font-semibold text-gray-900 mb-2", children: "4. Configure GhostBe App" }), _jsxs("ul", { className: "list-disc list-inside text-gray-700 space-y-1", children: [_jsx("li", { children: "Install the GhostBe Android app" }), _jsx("li", { children: "Enter your PC's IP address and proxy port (default 8877)" }), _jsx("li", { children: "Select which apps to intercept" }), _jsx("li", { children: "Tap \"Connect\"" })] })] }), _jsxs("div", { children: [_jsx("h4", { className: "font-semibold text-gray-900 mb-2", children: "5. Start Testing" }), _jsx("p", { className: "text-gray-700", children: "Open the app you want to test. Requests will appear in the Traffic tab." })] })] })] })] }));
}
