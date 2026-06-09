package certs

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

type Manager struct {
	caCert *x509.Certificate
	caKey  *rsa.PrivateKey
	dataDir string
}

// New creates or loads a root CA
func New(dataDir string) (*Manager, error) {
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		return nil, err
	}

	caCertPath := filepath.Join(dataDir, "ca.crt")
	caKeyPath := filepath.Join(dataDir, "ca.key")

	var caCert *x509.Certificate
	var caKey *rsa.PrivateKey
	var err error

	// Try to load existing CA
	if _, err := os.Stat(caCertPath); err == nil && os.IsExist(err) == false {
		caCert, caKey, err = loadCA(caCertPath, caKeyPath)
		if err != nil {
			return nil, fmt.Errorf("failed to load CA: %w", err)
		}
	} else {
		// Generate new CA
		caCert, caKey, err = generateRootCA()
		if err != nil {
			return nil, fmt.Errorf("failed to generate CA: %w", err)
		}

		// Save CA
		if err := saveCA(caCert, caKey, caCertPath, caKeyPath); err != nil {
			return nil, fmt.Errorf("failed to save CA: %w", err)
		}
	}

	return &Manager{
		caCert:  caCert,
		caKey:   caKey,
		dataDir: dataDir,
	}, nil
}

// GetCACertificate returns the CA certificate
func (m *Manager) GetCACertificate() *x509.Certificate {
	return m.caCert
}

// GetCAKey returns the CA private key
func (m *Manager) GetCAKey() *rsa.PrivateKey {
	return m.caKey
}

// GetCAFingerprint returns the SHA256 fingerprint of the CA cert
func (m *Manager) GetCAFingerprint() string {
	hash := sha256.Sum256(m.caCert.Raw)
	return fmt.Sprintf("%X", hash)
}

// SignLeafCert signs a leaf certificate for a hostname
func (m *Manager) SignLeafCert(hostname string) (tls.Certificate, error) {
	// Generate leaf key
	leafKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return tls.Certificate{}, err
	}

	// Create leaf cert template
	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return tls.Certificate{}, err
	}

	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			CommonName: hostname,
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{hostname},
	}

	// Sign leaf cert with CA
	leafCertDER, err := x509.CreateCertificate(rand.Reader, &template, m.caCert, &leafKey.PublicKey, m.caKey)
	if err != nil {
		return tls.Certificate{}, err
	}

	// Create tls.Certificate
	return tls.Certificate{
		Certificate: [][]byte{leafCertDER, m.caCert.Raw},
		PrivateKey:  leafKey,
	}, nil
}

// Helper functions

func generateRootCA() (*x509.Certificate, *rsa.PrivateKey, error) {
	caKey, err := rsa.GenerateKey(rand.Reader, 4096)
	if err != nil {
		return nil, nil, err
	}

	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, err
	}

	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			CommonName:         "GhostBe Root CA",
			Organization:       []string{"GhostBe"},
			OrganizationalUnit: []string{"MITM Proxy"},
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}

	certDER, err := x509.CreateCertificate(rand.Reader, &template, &template, &caKey.PublicKey, caKey)
	if err != nil {
		return nil, nil, err
	}

	caCert, err := x509.ParseCertificate(certDER)
	if err != nil {
		return nil, nil, err
	}

	return caCert, caKey, nil
}

func saveCA(cert *x509.Certificate, key *rsa.PrivateKey, certPath, keyPath string) error {
	// Save cert
	certPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "CERTIFICATE",
		Bytes: cert.Raw,
	})
	if err := os.WriteFile(certPath, certPEM, 0644); err != nil {
		return err
	}

	// Save key
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "PRIVATE KEY",
		Bytes: keyDER,
	})
	if err := os.WriteFile(keyPath, keyPEM, 0600); err != nil {
		return err
	}

	return nil
}

func loadCA(certPath, keyPath string) (*x509.Certificate, *rsa.PrivateKey, error) {
	// Load cert
	certPEM, err := os.ReadFile(certPath)
	if err != nil {
		return nil, nil, err
	}
	block, _ := pem.Decode(certPEM)
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, nil, err
	}

	// Load key
	keyPEM, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, nil, err
	}
	block, _ = pem.Decode(keyPEM)
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, nil, err
	}

	rsaKey, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, nil, fmt.Errorf("key is not RSA")
	}

	return cert, rsaKey, nil
}
