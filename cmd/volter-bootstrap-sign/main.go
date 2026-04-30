package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"

	"dev.c0redev.volter/internal/discovery"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "keygen":
		keygen(os.Args[2:])
	case "sign":
		sign(os.Args[2:])
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintf(os.Stderr, `usage:
  %s keygen -out PATH.pem
       Generate Ed25519 key; prints VOLTER_BOOTSTRAP_PUB_KEY=<base64 raw32> for clients.

  %s sign -key PATH.pem -nodes PATH.json -out PATH.json [-epoch SEC] [-expires UNIX]
       nodes JSON: {"nodes":[...RelayNode...]}
`, os.Args[0], os.Args[0])
}

func keygen(args []string) {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	out := fs.String("out", "volter-bootstrap.pem", "write PKCS8 PEM private key")
	_ = fs.Parse(args)
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		die(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		die(err)
	}
	block := &pem.Block{Type: "PRIVATE KEY", Bytes: der}
	b := pem.EncodeToMemory(block)
	if err := os.WriteFile(*out, b, 0600); err != nil {
		die(err)
	}
	fmt.Printf("VOLTER_BOOTSTRAP_PUB_KEY=%s\n", base64.RawStdEncoding.EncodeToString(pub))
	fmt.Printf("private_key_file=%s\n", *out)
}

func sign(args []string) {
	fs := flag.NewFlagSet("sign", flag.ExitOnError)
	keyPath := fs.String("key", "", "PEM private key from keygen")
	nodesPath := fs.String("nodes", "", "JSON with {\"nodes\":[...]}")
	outPath := fs.String("out", "bootstrap-signed.json", "signed bootstrap output")
	epoch := fs.Int64("epoch", time.Now().Unix(), "epochSec")
	expires := fs.Int64("expires", time.Now().Add(365*24*time.Hour).Unix(), "expiresAt unix")
	_ = fs.Parse(args)
	if *keyPath == "" || *nodesPath == "" {
		fs.Usage()
		os.Exit(2)
	}
	raw, err := os.ReadFile(*nodesPath)
	if err != nil {
		die(err)
	}
	var nf struct {
		Nodes []discovery.RelayNode `json:"nodes"`
	}
	if err := json.Unmarshal(raw, &nf); err != nil {
		die(err)
	}
	priv, err := loadEd25519Priv(*keyPath)
	if err != nil {
		die(err)
	}
	sb, err := discovery.SignBootstrap(*epoch, *expires, nf.Nodes, priv)
	if err != nil {
		die(err)
	}
	out, err := json.MarshalIndent(sb, "", "  ")
	if err != nil {
		die(err)
	}
	if err := os.WriteFile(*outPath, out, 0644); err != nil {
		die(err)
	}
	fmt.Printf("wrote %s\n", *outPath)
}

func loadEd25519Priv(path string) (ed25519.PrivateKey, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(b)
	if block == nil {
		return nil, errors.New("no PEM block")
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	priv, ok := k.(ed25519.PrivateKey)
	if !ok {
		return nil, errors.New("not ed25519 private key")
	}
	return priv, nil
}

func die(err error) {
	fmt.Fprintf(os.Stderr, "volter-bootstrap-sign: %v\n", err)
	os.Exit(1)
}
