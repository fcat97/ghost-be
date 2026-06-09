package rules

import (
	"path/filepath"
	"regexp"
	"strings"

	"github.com/ghostbe/backend/internal/db"
)

type Engine struct {
	rules []db.Rule
}

func New(rules []db.Rule) *Engine {
	return &Engine{rules: rules}
}

// Match finds a rule for the given app, method, and URL
func (e *Engine) Match(appPackage, method, url string) *db.Rule {
	for _, rule := range e.rules {
		if !rule.Enabled {
			continue
		}

		// Check app package
		if rule.AppPackage != appPackage {
			continue
		}

		// Check method (if specified)
		if rule.Method != "*" && !strings.EqualFold(rule.Method, method) {
			continue
		}

		// Check URL pattern
		if matchURLPattern(rule.URLPattern, url) {
			return &rule
		}
	}

	return nil
}

// Update updates the rules list
func (e *Engine) Update(rules []db.Rule) {
	e.rules = rules
}

// Helper to match glob or regex patterns
func matchURLPattern(pattern, url string) bool {
	// Try glob first
	if matched, err := filepath.Match(pattern, url); err == nil && matched {
		return true
	}

	// Try regex
	if re, err := regexp.Compile(pattern); err == nil && re.MatchString(url) {
		return true
	}

	// Exact match
	if pattern == url {
		return true
	}

	return false
}
