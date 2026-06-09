import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { useState } from 'react';
import { useRules } from '../hooks/useRules';
export default function Rules() {
    const { rules, loading, error, createRule, updateRule, deleteRule, toggleRule } = useRules();
    const [showModal, setShowModal] = useState(false);
    const [editingRule, setEditingRule] = useState(null);
    const [formData, setFormData] = useState({
        app_package: '',
        url_pattern: '',
        method: '*',
        response_status: 200,
        response_body: '',
        enabled: true,
    });
    const groupedRules = rules.reduce((acc, rule) => {
        if (!acc[rule.app_package]) {
            acc[rule.app_package] = [];
        }
        acc[rule.app_package].push(rule);
        return acc;
    }, {});
    const handleOpenModal = (rule) => {
        if (rule) {
            setEditingRule(rule);
            setFormData({
                app_package: rule.app_package,
                url_pattern: rule.url_pattern,
                method: rule.method,
                response_status: rule.response_status,
                response_body: rule.response_body || '',
                enabled: rule.enabled,
            });
        }
        else {
            setEditingRule(null);
            setFormData({
                app_package: '',
                url_pattern: '',
                method: '*',
                response_status: 200,
                response_body: '',
                enabled: true,
            });
        }
        setShowModal(true);
    };
    const handleCloseModal = () => {
        setShowModal(false);
        setEditingRule(null);
    };
    const handleSubmit = async (e) => {
        e.preventDefault();
        try {
            if (editingRule) {
                await updateRule(editingRule.id, {
                    ...formData,
                    response_body: formData.response_body || null,
                });
            }
            else {
                await createRule({
                    ...formData,
                    response_body: formData.response_body || null,
                });
            }
            handleCloseModal();
        }
        catch (err) {
            console.error('Failed to save rule:', err);
        }
    };
    const handleDelete = async (id) => {
        if (confirm('Are you sure you want to delete this rule?')) {
            try {
                await deleteRule(id);
            }
            catch (err) {
                console.error('Failed to delete rule:', err);
            }
        }
    };
    if (loading) {
        return _jsx("div", { className: "py-8 text-center text-gray-500", children: "Loading rules..." });
    }
    return (_jsxs("div", { className: "py-8", children: [_jsxs("div", { className: "mb-6 flex items-center justify-between", children: [_jsx("h2", { className: "text-2xl font-bold text-gray-900", children: "Traffic Rules" }), _jsx("button", { onClick: () => handleOpenModal(), className: "px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors", children: "+ New Rule" })] }), error && (_jsx("div", { className: "mb-4 p-4 bg-red-50 border border-red-200 rounded-lg text-red-700", children: error })), Object.keys(groupedRules).length === 0 ? (_jsx("div", { className: "text-center py-12 text-gray-500", children: "No rules yet. Create one to get started." })) : (_jsx("div", { className: "space-y-4", children: Object.entries(groupedRules).map(([appPackage, appRules]) => (_jsxs("div", { className: "border border-gray-200 rounded-lg overflow-hidden", children: [_jsx("div", { className: "bg-gray-50 px-4 py-3 font-semibold text-gray-900", children: appPackage }), _jsx("div", { className: "divide-y divide-gray-200", children: appRules.map((rule) => (_jsxs("div", { className: "px-4 py-3 flex items-center gap-4", children: [_jsx("label", { className: "flex items-center gap-2", children: _jsx("input", { type: "checkbox", checked: rule.enabled, onChange: () => toggleRule(rule.id), className: "rounded" }) }), _jsxs("div", { className: "flex-1 min-w-0", children: [_jsx("div", { className: "text-sm font-mono text-gray-900 truncate", children: rule.url_pattern }), _jsxs("div", { className: "text-xs text-gray-500", children: [rule.method, " \u2192 ", rule.response_status] })] }), _jsx("button", { onClick: () => handleOpenModal(rule), className: "px-3 py-1 text-sm bg-gray-100 text-gray-700 rounded hover:bg-gray-200 transition-colors", children: "Edit" }), _jsx("button", { onClick: () => handleDelete(rule.id), className: "px-3 py-1 text-sm bg-red-100 text-red-700 rounded hover:bg-red-200 transition-colors", children: "Delete" })] }, rule.id))) })] }, appPackage))) })), showModal && (_jsx("div", { className: "fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50", children: _jsxs("div", { className: "bg-white rounded-lg shadow-xl max-w-md w-full mx-4 p-6", children: [_jsx("h3", { className: "text-lg font-bold text-gray-900 mb-4", children: editingRule ? 'Edit Rule' : 'New Rule' }), _jsxs("form", { onSubmit: handleSubmit, className: "space-y-4", children: [_jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-1", children: "App Package" }), _jsx("input", { type: "text", value: formData.app_package, onChange: (e) => setFormData({ ...formData, app_package: e.target.value }), placeholder: "com.example.app", className: "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500", required: true })] }), _jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-1", children: "URL Pattern (glob or regex)" }), _jsx("input", { type: "text", value: formData.url_pattern, onChange: (e) => setFormData({ ...formData, url_pattern: e.target.value }), placeholder: "*.example.com/* or ^https://api\\\\..*", className: "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500", required: true })] }), _jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-1", children: "Method" }), _jsxs("select", { value: formData.method, onChange: (e) => setFormData({ ...formData, method: e.target.value }), className: "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500", children: [_jsx("option", { value: "*", children: "* (All)" }), _jsx("option", { value: "GET", children: "GET" }), _jsx("option", { value: "POST", children: "POST" }), _jsx("option", { value: "PUT", children: "PUT" }), _jsx("option", { value: "DELETE", children: "DELETE" }), _jsx("option", { value: "PATCH", children: "PATCH" })] })] }), _jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-1", children: "Response Status" }), _jsx("input", { type: "number", value: formData.response_status, onChange: (e) => setFormData({ ...formData, response_status: parseInt(e.target.value) }), className: "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500" })] }), _jsxs("div", { children: [_jsx("label", { className: "block text-sm font-medium text-gray-700 mb-1", children: "Response Body (JSON)" }), _jsx("textarea", { value: formData.response_body, onChange: (e) => setFormData({ ...formData, response_body: e.target.value }), placeholder: '{"error": "Mocked response"}', className: "w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-sm", rows: 4 })] }), _jsxs("label", { className: "flex items-center gap-2", children: [_jsx("input", { type: "checkbox", checked: formData.enabled, onChange: (e) => setFormData({ ...formData, enabled: e.target.checked }), className: "rounded" }), _jsx("span", { className: "text-sm text-gray-700", children: "Enabled" })] }), _jsxs("div", { className: "flex gap-3 pt-4", children: [_jsx("button", { type: "button", onClick: handleCloseModal, className: "flex-1 px-4 py-2 text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors", children: "Cancel" }), _jsx("button", { type: "submit", className: "flex-1 px-4 py-2 text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors", children: "Save" })] })] })] }) }))] }));
}
