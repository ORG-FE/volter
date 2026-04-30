package ice

import (
	"context"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"
)

type TurnResult struct {
	Relayed net.UDPAddr
	RTT     time.Duration
	Server  string
}

func TryTurnAllocate(ctx context.Context, turnURL string) (*TurnResult, error) {
	hostPort, user, pass, err := parseTurnURL(turnURL)
	if err != nil {
		return nil, err
	}
	addr, err := net.ResolveUDPAddr("udp", hostPort)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{}
	conn, err := d.DialContext(ctx, "udp", addr.String())
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	uc, ok := conn.(*net.UDPConn)
	if !ok {
		return nil, errors.New("ice: turn dial not udp")
	}

	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return nil, err
	}

	deadline, hasDL := ctx.Deadline()
	if !hasDL {
		deadline = time.Now().Add(8 * time.Second)
	}
	_ = uc.SetDeadline(deadline)

	t0 := time.Now()
	req := buildAllocateFirst(txID)
	if _, err := uc.Write(req); err != nil {
		return nil, err
	}
	buf := make([]byte, 2048)
	n, err := uc.Read(buf)
	if err != nil {
		return nil, err
	}
	rtt := time.Since(t0)

	relIP, relPort, realm, nonce, errCode := parseAllocateResponse(buf[:n], txID)
	if relIP != nil && errCode == 0 {
		return &TurnResult{Relayed: net.UDPAddr{IP: relIP, Port: int(relPort)}, RTT: rtt, Server: hostPort}, nil
	}
	if user == "" || pass == "" || realm == "" || nonce == "" {
		if errCode != 0 {
			return nil, fmt.Errorf("turn: allocate failed code=%d", errCode)
		}
		return nil, errors.New("turn: no relayed address (set turn:user:pass@host:port)")
	}

	var txID2 [12]byte
	if _, err := rand.Read(txID2[:]); err != nil {
		return nil, err
	}
	t1 := time.Now()
	req2, err := buildAllocateAuth(txID2, user, pass, realm, nonce)
	if err != nil {
		return nil, err
	}
	if _, err := uc.Write(req2); err != nil {
		return nil, err
	}
	n2, err := uc.Read(buf)
	if err != nil {
		return nil, err
	}
	rtt2 := time.Since(t1)
	relIP2, relPort2, _, _, errCode2 := parseAllocateResponse(buf[:n2], txID2)
	if errCode2 != 0 {
		return nil, fmt.Errorf("turn: allocate auth failed code=%d", errCode2)
	}
	if relIP2 == nil {
		return nil, errors.New("turn: no relayed address after auth")
	}
	return &TurnResult{Relayed: net.UDPAddr{IP: relIP2, Port: int(relPort2)}, RTT: rtt2, Server: hostPort}, nil
}

func parseTurnURL(raw string) (hostPort, user, pass string, err error) {
	s := strings.TrimSpace(raw)
	s = strings.TrimPrefix(s, "turn:")
	s = strings.TrimPrefix(s, "turn://")
	if s == "" {
		return "", "", "", errors.New("turn: empty url")
	}
	if i := strings.IndexByte(s, '@'); i >= 0 {
		userPass := s[:i]
		hostPort = s[i+1:]
		if j := strings.IndexByte(userPass, ':'); j >= 0 {
			user = userPass[:j]
			pass = userPass[j+1:]
		} else {
			user = userPass
		}
	} else {
		hostPort = s
	}
	if hostPort == "" {
		return "", "", "", errors.New("turn: missing host")
	}
	return hostPort, user, pass, nil
}

func buildAllocateFirst(txID [12]byte) []byte {
	rt := []byte{17, 0, 0, 0}
	attr := packAttr(attrRequestedTransport, rt)
	bodyLen := len(attr)
	out := make([]byte, 20+bodyLen)
	binary.BigEndian.PutUint16(out[0:2], typAllocateRequest)
	binary.BigEndian.PutUint16(out[2:4], uint16(bodyLen))
	binary.BigEndian.PutUint32(out[4:8], stunMagicCookie)
	copy(out[8:20], txID[:])
	copy(out[20:], attr)
	return out
}

func buildAllocateAuth(txID [12]byte, user, pass, realm, nonce string) ([]byte, error) {
	var body []byte
	body = append(body, packAttr(attrRequestedTransport, []byte{17, 0, 0, 0})...)
	body = append(body, packAttr(attrUsername, []byte(user))...)
	body = append(body, packAttr(attrRealm, []byte(realm))...)
	body = append(body, packAttr(attrNonce, []byte(nonce))...)
	body = append(body, packAttr(attrMessageIntegrity, make([]byte, 20))...)

	hdr := make([]byte, 20)
	binary.BigEndian.PutUint16(hdr[0:2], typAllocateRequest)
	binary.BigEndian.PutUint16(hdr[2:4], uint16(len(body)))
	binary.BigEndian.PutUint32(hdr[4:8], stunMagicCookie)
	copy(hdr[8:20], txID[:])

	msg := append(hdr, body...)
	key := md5.Sum([]byte(user + ":" + realm + ":" + pass))
	mac := hmac.New(sha1.New, key[:])
	_, _ = mac.Write(msg)
	sum := mac.Sum(nil)
	copy(msg[len(msg)-20:], sum[:20])
	return msg, nil
}

func packAttr(typ uint16, val []byte) []byte {
	pad := (len(val) + 3) &^ 3
	out := make([]byte, 4+pad)
	binary.BigEndian.PutUint16(out[0:2], typ)
	binary.BigEndian.PutUint16(out[2:4], uint16(len(val)))
	copy(out[4:], val)
	return out
}

func parseAllocateResponse(pkt []byte, wantTx [12]byte) (relIP net.IP, relPort uint16, realm, nonce string, errCode int) {
	if len(pkt) < 20 {
		return nil, 0, "", "", -1
	}
	if binary.BigEndian.Uint32(pkt[4:8]) != stunMagicCookie {
		return nil, 0, "", "", -1
	}
	var tx [12]byte
	copy(tx[:], pkt[8:20])
	if tx != wantTx {
		return nil, 0, "", "", -1
	}
	length := int(binary.BigEndian.Uint16(pkt[2:4]))
	pos := 20
	end := 20 + length
	if end > len(pkt) {
		end = len(pkt)
	}
	for pos+4 <= end {
		at := binary.BigEndian.Uint16(pkt[pos : pos+2])
		al := int(binary.BigEndian.Uint16(pkt[pos+2 : pos+4]))
		pos += 4
		pad := (al + 3) &^ 3
		if pos+al > len(pkt) {
			break
		}
		val := pkt[pos : pos+al]
		pos += pad
		switch at {
		case attrXorRelayedAddress:
			ip, port := decodeXorMapped(val, tx)
			if ip != nil {
				relIP, relPort = ip, port
			}
		case attrRealm:
			realm = string(val)
		case attrNonce:
			nonce = string(val)
		case attrErrorCode:
			errCode = parseErrorCodeAttr(val)
		}
	}
	return relIP, relPort, realm, nonce, errCode
}

func parseErrorCodeAttr(val []byte) int {
	if len(val) < 4 {
		return 0
	}
	return int(val[2])*100 + int(val[3]%100)
}

const (
	typAllocateRequest     = 0x0003
	attrRequestedTransport = 0x0019
	attrUsername           = 0x0006
	attrMessageIntegrity   = 0x0008
	attrErrorCode          = 0x0009
	attrRealm              = 0x0014
	attrNonce              = 0x0015
	attrXorRelayedAddress  = 0x0016
)
