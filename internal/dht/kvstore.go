package dht

import (
	"sync"
	"time"
)

const (
	KVMaxValue = 4096
	KVMaxKeys  = 8192
)

type KVStore struct {
	mu sync.RWMutex
	m  map[[32]byte]kvRec
}

type kvRec struct {
	v   []byte
	exp time.Time
}

func NewKVStore() *KVStore {
	return &KVStore{m: make(map[[32]byte]kvRec)}
}

var defaultKVStore = NewKVStore()

func DefaultKVStore() *KVStore {
	return defaultKVStore
}

func (s *KVStore) Put(key [32]byte, ttl time.Duration, val []byte) bool {
	if len(val) > KVMaxValue {
		val = val[:KVMaxValue]
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.m) >= KVMaxKeys && s.mustEvictLocked(key) {
		return false
	}
	exp := time.Now().Add(ttl)
	if ttl <= 0 {
		exp = time.Now().Add(24 * time.Hour)
	}
	cp := append([]byte(nil), val...)
	s.m[key] = kvRec{v: cp, exp: exp}
	return true
}

func (s *KVStore) mustEvictLocked(skip [32]byte) bool {
	var oldest [32]byte
	var t0 time.Time
	first := true
	for k, r := range s.m {
		if k == skip {
			continue
		}
		if first || r.exp.Before(t0) {
			first = false
			oldest = k
			t0 = r.exp
		}
	}
	if first {
		return true
	}
	delete(s.m, oldest)
	return false
}

func (s *KVStore) Get(key [32]byte) ([]byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.m[key]
	if !ok || time.Now().After(r.exp) {
		if ok {
			delete(s.m, key)
		}
		return nil, false
	}
	return append([]byte(nil), r.v...), true
}
