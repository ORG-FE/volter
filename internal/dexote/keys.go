package dexote

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"sync"
	"time"
)

func GenerateServerKey() (scalar, pub []byte, err error) {
	scalar = make([]byte, keyLen)
	if _, err = rand.Read(scalar); err != nil {
		return nil, nil, err
	}
	pub, err = pubFromScalar(scalar)
	if err != nil {
		return nil, nil, err
	}
	return scalar, pub, nil
}

func PubFromScalar(scalar []byte) ([]byte, error) {
	return pubFromScalar(scalar)
}

func EncodePub(pub []byte) string {
	return base64.StdEncoding.EncodeToString(pub)
}

func DecodePub(s string) ([]byte, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return nil, err
	}
	if len(b) != keyLen {
		return nil, errors.New("dexote: pubkey must be 32 bytes")
	}
	return b, nil
}

type ReplayCache interface {
	Add(nonce []byte, tsSec int64) bool
}

type MemReplayCache struct {
	mu     sync.Mutex
	seen   map[string]int64
	window int64
}

func NewMemReplayCache() *MemReplayCache {
	return &MemReplayCache{seen: make(map[string]int64), window: replayWindow * 2}
}

func (c *MemReplayCache) Add(nonce []byte, tsSec int64) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now().Unix()

	for k, ins := range c.seen {
		if ins < now-c.window {
			delete(c.seen, k)
		}
	}
	key := string(nonce)
	if _, ok := c.seen[key]; ok {
		return false
	}
	c.seen[key] = now
	return true
}
