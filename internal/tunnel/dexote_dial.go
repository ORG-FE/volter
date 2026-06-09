package tunnel

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"dev.c0redev.volter/internal/config"
	"dev.c0redev.volter/internal/dexote"
	"dev.c0redev.volter/internal/obfuscate"
	"dev.c0redev.volter/internal/protocol"
)

var (
	dexoteServerPubMu sync.RWMutex
	dexoteServerPub   []byte
)

func SetDexoteServerPub(pub []byte) {
	dexoteServerPubMu.Lock()
	defer dexoteServerPubMu.Unlock()
	if len(pub) == 0 {
		dexoteServerPub = nil
		return
	}
	dexoteServerPub = append([]byte(nil), pub...)
}

func getDexoteServerPub() []byte {
	dexoteServerPubMu.RLock()
	defer dexoteServerPubMu.RUnlock()
	return dexoteServerPub
}

func dexotePayload(token string, prot *config.ProtectionOptions) (byte, []byte) {
	role := protocol.RoleTCP()
	var optsJSON []byte
	if prot != nil {
		enrichSessionOptions(prot, token)
		eff := relayOptsForHandshake(prot, token)
		optsJSON, _ = json.Marshal(eff)
		clusterExit := strings.TrimSpace(prot.ClusterPreferredServer) != ""
		hasPeerRelay := strings.TrimSpace(eff.PeerID) != ""
		needRelay := (eff.RelayHop > 0 || eff.RelayMaxHop > 0) && !clusterExit && hasPeerRelay
		if needRelay {
			role = protocol.RoleRelayTCP()
		}
	}
	return role, optsJSON
}

func dexotePadMax(prot *config.ProtectionOptions) int {
	if prot != nil && prot.PadS4 > 0 {
		if prot.PadS4 > 1024 {
			return 1024
		}
		return prot.PadS4
	}
	return 64
}

func dexoteUDPPayload(token string, prot *config.ProtectionOptions, channelID byte) []byte {
	out := []byte{channelID}
	if prot != nil {
		enrichSessionOptions(prot, token)
		if js, err := json.Marshal(prot); err == nil {
			out = append(out, js...)
		}
	}
	return out
}

func dialServerDexote(addr, token string, prot *config.ProtectionOptions, slot int64, role byte, optsJSON []byte) (net.Conn, []byte, error) {
	pub := getDexoteServerPub()
	if len(pub) == 0 {
		return nil, nil, fmt.Errorf("dexote: server pubkey not configured (set dexoteServerPub)")
	}
	c, err := dialServerRaw(addr)
	if err != nil {
		return nil, nil, err
	}
	_ = c.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	keys, caps, err := dexote.ClientHandshake(c, pub, slot, dexote.ClientHelloPayload{
		Role:  role,
		Token: token,
		Opts:  optsJSON,
	})
	if err != nil {
		_ = c.Close()
		return nil, nil, fmt.Errorf("dexote handshake %s: %w", addr, err)
	}
	padMax := dexotePadMax(prot)
	wrapped := obfuscate.WrapAEADShaped(c, keys,
		dexote.NewPoly(keys.Secret, slot, "tx"),
		dexote.NewPoly(keys.Secret, slot, "rx"), padMax,
		buildAEADShapeHook(prot, slot))
	return wrapped, caps, nil
}

func newDexoteRW(c net.Conn, slot int64) (*bufio.Reader, *bufio.Writer) {
	bufSize := protocol.BufSizeForConn(slot)
	return bufio.NewReaderSize(c, bufSize), bufio.NewWriterSize(c, bufSize)
}

func DialUDPChannelDexote(addr string, channelID byte, token string, prot *config.ProtectionOptions) (net.Conn, *bufio.Reader, *bufio.Writer, int, error) {
	pub := getDexoteServerPub()
	if len(pub) == 0 {
		return nil, nil, nil, 0, fmt.Errorf("dexote: server pubkey not configured (set dexoteServerPub)")
	}
	c, err := dialServerRaw(addr)
	if err != nil {
		return nil, nil, nil, 0, err
	}
	slot := SlotForProtection(prot)
	_ = c.SetDeadline(time.Now().Add(volterWireHandshakeTimeout))
	keys, _, err := dexote.ClientHandshake(c, pub, slot, dexote.ClientHelloPayload{
		Role:  protocol.RoleUDP(),
		Token: token,
		Opts:  dexoteUDPPayload(token, prot, channelID),
	})
	if err != nil {
		_ = c.Close()
		return nil, nil, nil, 0, fmt.Errorf("dexote udp handshake %s: %w", addr, err)
	}
	_ = c.SetDeadline(time.Time{})
	wrapped := obfuscate.WrapAEAD(c, keys,
		dexote.NewPoly(keys.Secret, slot, "tx"),
		dexote.NewPoly(keys.Secret, slot, "rx"), dexotePadMax(prot))
	r, w := newDexoteRW(wrapped, slot)
	return wrapped, r, w, 0, nil
}
