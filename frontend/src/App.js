import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import Traffic from './pages/Traffic';
import Rules from './pages/Rules';
import Apps from './pages/Apps';
import Settings from './pages/Settings';
export default function App() {
    return (_jsx(BrowserRouter, { children: _jsxs("div", { className: "min-h-screen bg-gray-50", children: [_jsx("nav", { className: "bg-white shadow-sm", children: _jsxs("div", { className: "max-w-7xl mx-auto px-4 py-4 flex items-center justify-between", children: [_jsx("h1", { className: "text-2xl font-bold text-gray-900", children: "GhostBe" }), _jsxs("div", { className: "flex gap-4", children: [_jsx(Link, { to: "/", className: "text-gray-700 hover:text-gray-900", children: "Traffic" }), _jsx(Link, { to: "/rules", className: "text-gray-700 hover:text-gray-900", children: "Rules" }), _jsx(Link, { to: "/apps", className: "text-gray-700 hover:text-gray-900", children: "Apps" }), _jsx(Link, { to: "/settings", className: "text-gray-700 hover:text-gray-900", children: "Settings" })] })] }) }), _jsx("main", { className: "max-w-7xl mx-auto", children: _jsxs(Routes, { children: [_jsx(Route, { path: "/", element: _jsx(Traffic, {}) }), _jsx(Route, { path: "/rules", element: _jsx(Rules, {}) }), _jsx(Route, { path: "/apps", element: _jsx(Apps, {}) }), _jsx(Route, { path: "/settings", element: _jsx(Settings, {}) })] }) })] }) }));
}
