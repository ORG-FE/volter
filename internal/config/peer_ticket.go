package config

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type PeerTicket struct {
	PeerID    string   `json:"peerId"`
	PubKey    string   `json:"pubKey"`
	Addrs     []string `json:"addrs"`
	ExpiresAt int64    `json:"expiresAt"`
	Nonce     string   `json:"nonce"`
	Sig       string   `json:"sig"`
}

type peerTicketEnvelope struct {
	V      int        `json:"v"`
	Type   string     `json:"t"`
	Ticket PeerTicket `json:"ticket"`
}

func BuildPeerTicketURI(t PeerTicket) string {
	env := peerTicketEnvelope{V: 1, Type: "peerTicket", Ticket: t}
	b, err := json.Marshal(env)
	if err != nil {
		return ""
	}
	return "volter://" + base64.RawURLEncoding.EncodeToString(b)
}

func ParsePeerTicketURI(raw string) (PeerTicket, bool) {
	s := strings.TrimSpace(raw)
	if !strings.HasPrefix(strings.ToLower(s), "volter://") {
		return PeerTicket{}, false
	}
	body := strings.TrimSpace(s[len("volter://"):])
	if i := strings.IndexAny(body, "?#"); i >= 0 {
		body = body[:i]
	}
	if body == "" {
		return PeerTicket{}, false
	}
	data, err := base64.RawURLEncoding.DecodeString(body)
	if err != nil {
		data, err = base64.URLEncoding.DecodeString(body)
	}
	if err != nil {
		return PeerTicket{}, false
	}
	var env peerTicketEnvelope
	if err := json.Unmarshal(data, &env); err != nil {
		return PeerTicket{}, false
	}
	if env.Type != "peerTicket" {
		return PeerTicket{}, false
	}
	t := sanitizePeerTicket(env.Ticket)
	if t.PeerID == "" || t.PubKey == "" || len(t.Addrs) == 0 || t.ExpiresAt <= time.Now().UnixMilli() {
		return PeerTicket{}, false
	}
	if t.Sig != peerTicketSig(t.PeerID, t.PubKey, t.Addrs, t.ExpiresAt, t.Nonce) {
		return PeerTicket{}, false
	}
	return t, true
}

func CreatePeerTicket(peerID, pubKey string, addrs []string, ttl time.Duration) PeerTicket {
	if ttl < time.Second {
		ttl = 24 * time.Hour
	}
	clean := make([]string, 0, len(addrs))
	seen := map[string]bool{}
	for _, a := range addrs {
		v := strings.TrimSpace(a)
		if v == "" || seen[v] {
			continue
		}
		seen[v] = true
		clean = append(clean, v)
	}
	exp := time.Now().Add(ttl).UnixMilli()
	nonceRaw := sha256.Sum256([]byte(strings.TrimSpace(peerID) + "|" + strings.TrimSpace(pubKey) + "|" + strings.Join(clean, ",") + "|" + strconvI64(exp)))
	nonce := hex.EncodeToString(nonceRaw[:])[:16]
	t := PeerTicket{
		PeerID:    strings.TrimSpace(peerID),
		PubKey:    strings.TrimSpace(pubKey),
		Addrs:     clean,
		ExpiresAt: exp,
		Nonce:     nonce,
	}
	t.Sig = peerTicketSig(t.PeerID, t.PubKey, t.Addrs, t.ExpiresAt, t.Nonce)
	return t
}

func LoadPeerTickets() ([]PeerTicket, error) {
	p := peerTicketStorePath()
	data, err := os.ReadFile(p)
	if err != nil {
		if os.IsNotExist(err) {
			return []PeerTicket{}, nil
		}
		return nil, err
	}
	var raw struct {
		V       int          `json:"v"`
		Tickets []PeerTicket `json:"tickets"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	out := make([]PeerTicket, 0, len(raw.Tickets))
	now := time.Now().UnixMilli()
	for _, t := range raw.Tickets {
		t = sanitizePeerTicket(t)
		if t.ExpiresAt <= now {
			continue
		}
		if t.Sig != peerTicketSig(t.PeerID, t.PubKey, t.Addrs, t.ExpiresAt, t.Nonce) {
			continue
		}
		out = append(out, t)
	}
	return out, nil
}

func UpsertPeerTicket(t PeerTicket) error {
	t = sanitizePeerTicket(t)
	all, err := LoadPeerTickets()
	if err != nil {
		return err
	}
	found := -1
	for i := range all {
		if all[i].PeerID == t.PeerID {
			found = i
			break
		}
	}
	if found >= 0 {
		all[found] = t
	} else {
		all = append(all, t)
	}
	return savePeerTickets(all)
}

func savePeerTickets(all []PeerTicket) error {
	p := peerTicketStorePath()
	_ = os.MkdirAll(filepath.Dir(p), 0o700)
	out := struct {
		V       int          `json:"v"`
		Tickets []PeerTicket `json:"tickets"`
	}{V: 1, Tickets: all}
	b, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(p, b, 0o600)
}

func peerTicketStorePath() string {
	base, err := Dir()
	if err != nil {
		return "peer-tickets.json"
	}
	return filepath.Join(base, "peer-tickets.json")
}

func sanitizePeerTicket(t PeerTicket) PeerTicket {
	t.PeerID = strings.TrimSpace(t.PeerID)
	t.PubKey = strings.TrimSpace(t.PubKey)
	t.Nonce = strings.TrimSpace(t.Nonce)
	t.Sig = strings.TrimSpace(strings.ToLower(t.Sig))
	out := make([]string, 0, len(t.Addrs))
	seen := map[string]bool{}
	for _, a := range t.Addrs {
		v := strings.TrimSpace(a)
		if v == "" || seen[v] {
			continue
		}
		seen[v] = true
		out = append(out, v)
	}
	t.Addrs = out
	return t
}

func peerTicketSig(peerID, pubKey string, addrs []string, expiresAt int64, nonce string) string {
	h := sha256.Sum256([]byte(strings.TrimSpace(peerID) + "|" + strings.TrimSpace(pubKey) + "|" + strings.Join(addrs, ",") + "|" + strconvI64(expiresAt) + "|" + strings.TrimSpace(nonce)))
	return hex.EncodeToString(h[:])
}

func strconvI64(v int64) string {
	return strconv.FormatInt(v, 10)
}
