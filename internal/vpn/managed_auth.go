package vpn

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"dev.c0redev.volter/internal/config"
)

func protectionWithManagedAuth(base *config.ProtectionOptions, managed *config.ManagedClient) *config.ProtectionOptions {
	if managed == nil || strings.TrimSpace(managed.ClientID) == "" || strings.TrimSpace(managed.Secret) == "" {
		return base
	}
	var out config.ProtectionOptions
	if base != nil {
		out = *base
	}
	nonce := randomB64(16)
	ts := time.Now().Unix()
	device := managedDeviceID()
	clientID := strings.TrimSpace(managed.ClientID)
	secretHash := sha256Hex(strings.TrimSpace(managed.Salt) + ":" + strings.TrimSpace(managed.Secret))
	msg := clientID + "|" + device + "|" + nonce + "|" + strconv.FormatInt(ts, 10)
	mac := hmac.New(sha256.New, []byte(secretHash))
	_, _ = mac.Write([]byte(msg))
	out.ManagedClientID = clientID
	out.ManagedDeviceID = device
	out.ManagedNonce = nonce
	out.ManagedTsSec = ts
	out.ManagedSig = base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return &out
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func managedDeviceID() string {
	h, _ := os.Hostname()
	h = strings.TrimSpace(h)
	if h == "" {
		h = "unknown"
	}
	return runtime.GOOS + ":" + h
}

func randomB64(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 10)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
