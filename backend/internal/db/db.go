package db

import (
	"database/sql"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

type DB struct {
	*sql.DB
}

func New(path string) (*DB, error) {
	sqlDB, err := sql.Open("sqlite3", path)
	if err != nil {
		return nil, err
	}

	if err := sqlDB.Ping(); err != nil {
		return nil, err
	}

	db := &DB{sqlDB}
	if err := db.migrate(); err != nil {
		return nil, err
	}

	return db, nil
}

func (db *DB) migrate() error {
	schema := `
	CREATE TABLE IF NOT EXISTS rules (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		app_package TEXT NOT NULL,
		url_pattern TEXT NOT NULL,
		method TEXT NOT NULL DEFAULT '*',
		response_status INTEGER NOT NULL DEFAULT 200,
		response_body TEXT,
		enabled INTEGER NOT NULL DEFAULT 1,
		created_at DATETIME DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS traffic_log (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
		app_package TEXT,
		method TEXT,
		url TEXT,
		request_headers TEXT,
		request_body TEXT,
		response_status INTEGER,
		response_body TEXT,
		latency_ms INTEGER,
		mocked INTEGER NOT NULL DEFAULT 0
	);
	`

	_, err := db.Exec(schema)
	return err
}

// Rule represents a traffic rule
type Rule struct {
	ID             int       `json:"id"`
	AppPackage     string    `json:"app_package"`
	URLPattern     string    `json:"url_pattern"`
	Method         string    `json:"method"`
	ResponseStatus int       `json:"response_status"`
	ResponseBody   *string   `json:"response_body"`
	Enabled        bool      `json:"enabled"`
	CreatedAt      time.Time `json:"created_at"`
}

// TrafficLog represents a logged request/response
type TrafficLog struct {
	ID             int       `json:"id"`
	Timestamp      time.Time `json:"timestamp"`
	AppPackage     *string   `json:"app_package"`
	Method         string    `json:"method"`
	URL            string    `json:"url"`
	RequestHeaders string    `json:"request_headers"`
	RequestBody    *string   `json:"request_body"`
	ResponseStatus int       `json:"response_status"`
	ResponseBody   *string   `json:"response_body"`
	LatencyMs      int64     `json:"latency_ms"`
	Mocked         bool      `json:"mocked"`
}

// GetRules returns all rules for an app package, or all if empty
func (db *DB) GetRules(appPackage string) ([]Rule, error) {
	query := "SELECT id, app_package, url_pattern, method, response_status, response_body, enabled, created_at FROM rules ORDER BY created_at DESC"
	if appPackage != "" {
		query += " WHERE app_package = ?"
	}

	var args []interface{}
	if appPackage != "" {
		args = append(args, appPackage)
	}

	rows, err := db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var rules []Rule
	for rows.Next() {
		var r Rule
		var enabled int
		if err := rows.Scan(&r.ID, &r.AppPackage, &r.URLPattern, &r.Method, &r.ResponseStatus, &r.ResponseBody, &enabled, &r.CreatedAt); err != nil {
			return nil, err
		}
		r.Enabled = enabled == 1
		rules = append(rules, r)
	}

	return rules, rows.Err()
}

// CreateRule creates a new rule
func (db *DB) CreateRule(r Rule) (*Rule, error) {
	result, err := db.Exec(
		"INSERT INTO rules (app_package, url_pattern, method, response_status, response_body, enabled) VALUES (?, ?, ?, ?, ?, ?)",
		r.AppPackage, r.URLPattern, r.Method, r.ResponseStatus, r.ResponseBody, boolToInt(r.Enabled),
	)
	if err != nil {
		return nil, err
	}

	id, err := result.LastInsertId()
	if err != nil {
		return nil, err
	}

	r.ID = int(id)
	r.CreatedAt = time.Now()
	return &r, nil
}

// UpdateRule updates an existing rule
func (db *DB) UpdateRule(r Rule) (*Rule, error) {
	_, err := db.Exec(
		"UPDATE rules SET app_package = ?, url_pattern = ?, method = ?, response_status = ?, response_body = ?, enabled = ? WHERE id = ?",
		r.AppPackage, r.URLPattern, r.Method, r.ResponseStatus, r.ResponseBody, boolToInt(r.Enabled), r.ID,
	)
	if err != nil {
		return nil, err
	}

	return &r, nil
}

// DeleteRule deletes a rule
func (db *DB) DeleteRule(id int) error {
	_, err := db.Exec("DELETE FROM rules WHERE id = ?", id)
	return err
}

// ToggleRule toggles the enabled flag
func (db *DB) ToggleRule(id int) (*Rule, error) {
	var r Rule
	var enabled int

	err := db.QueryRow(
		"UPDATE rules SET enabled = NOT enabled WHERE id = ? RETURNING id, app_package, url_pattern, method, response_status, response_body, enabled, created_at",
		id,
	).Scan(&r.ID, &r.AppPackage, &r.URLPattern, &r.Method, &r.ResponseStatus, &r.ResponseBody, &enabled, &r.CreatedAt)

	r.Enabled = enabled == 1
	return &r, err
}

// LogTraffic logs a request/response
func (db *DB) LogTraffic(t TrafficLog) error {
	_, err := db.Exec(
		"INSERT INTO traffic_log (app_package, method, url, request_headers, request_body, response_status, response_body, latency_ms, mocked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
		t.AppPackage, t.Method, t.URL, t.RequestHeaders, t.RequestBody, t.ResponseStatus, t.ResponseBody, t.LatencyMs, boolToInt(t.Mocked),
	)
	return err
}

// GetTraffic returns paginated traffic logs
func (db *DB) GetTraffic(appPackage string, limit, offset int) ([]TrafficLog, int, error) {
	query := "SELECT id, timestamp, app_package, method, url, request_headers, request_body, response_status, response_body, latency_ms, mocked FROM traffic_log"

	var args []interface{}
	if appPackage != "" {
		query += " WHERE app_package = ?"
		args = append(args, appPackage)
	}

	// Get total count
	countQuery := "SELECT COUNT(*) FROM traffic_log"
	if appPackage != "" {
		countQuery += " WHERE app_package = ?"
	}

	var total int
	if err := db.QueryRow(countQuery, args...).Scan(&total); err != nil {
		return nil, 0, err
	}

	query += " ORDER BY timestamp DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)

	rows, err := db.Query(query, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var logs []TrafficLog
	for rows.Next() {
		var t TrafficLog
		var mocked int
		if err := rows.Scan(&t.ID, &t.Timestamp, &t.AppPackage, &t.Method, &t.URL, &t.RequestHeaders, &t.RequestBody, &t.ResponseStatus, &t.ResponseBody, &t.LatencyMs, &mocked); err != nil {
			return nil, 0, err
		}
		t.Mocked = mocked == 1
		logs = append(logs, t)
	}

	return logs, total, rows.Err()
}

// GetAppStats returns request count per app
func (db *DB) GetAppStats() (map[string]int, error) {
	rows, err := db.Query("SELECT app_package, COUNT(*) as count FROM traffic_log WHERE app_package IS NOT NULL GROUP BY app_package")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	stats := make(map[string]int)
	for rows.Next() {
		var pkg string
		var count int
		if err := rows.Scan(&pkg, &count); err != nil {
			return nil, err
		}
		stats[pkg] = count
	}

	return stats, rows.Err()
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
