package dht

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"

	"dev.c0redev.volter/internal/discovery"
)

const (
	rpcMagic0 = "V"
	rpcMagic1 = "L"
	rpcMagic2 = "D"
	rpcMagic3 = "R"
	rpcVer    = 1
	rpcHdr    = 12
	rpcMacLen = 32
	rpcMaxPay = 48000
)

const (
	OpPing     = 1
	OpFindNode = 2
	OpStore    = 3
	OpGet      = 4
	opResp     = 0x80
)

func signPacket(secret string, body []byte) []byte {
	if secret == "" {
		return nil
	}
	m := hmac.New(sha256.New, []byte(secret))
	_, _ = m.Write(body)
	return m.Sum(nil)
}

func appendMAC(secret string, pkt []byte) []byte {
	if secret == "" {
		return pkt
	}
	mac := signPacket(secret, pkt)
	out := make([]byte, len(pkt)+rpcMacLen)
	copy(out, pkt)
	copy(out[len(pkt):], mac)
	return out
}

func verifyMAC(secret string, pkt []byte) ([]byte, bool) {
	if secret == "" {
		return pkt, true
	}
	if len(pkt) < rpcMacLen {
		return nil, false
	}
	body := pkt[:len(pkt)-rpcMacLen]
	want := pkt[len(pkt)-rpcMacLen:]
	got := signPacket(secret, body)
	if !hmac.Equal(want, got) {
		return nil, false
	}
	return body, true
}

func encodeRequest(reqID uint32, op byte, body []byte) []byte {
	if len(body) > rpcMaxPay {
		body = body[:rpcMaxPay]
	}
	out := make([]byte, rpcHdr+len(body))
	out[0] = rpcMagic0[0]
	out[1] = rpcMagic1[0]
	out[2] = rpcMagic2[0]
	out[3] = rpcMagic3[0]
	out[4] = rpcVer
	out[5] = op
	binary.BigEndian.PutUint32(out[6:10], reqID)
	binary.BigEndian.PutUint16(out[10:12], uint16(len(body)))
	copy(out[12:], body)
	return out
}

func encodePingReq(reqID uint32) []byte {
	return encodeRequest(reqID, OpPing, nil)
}

func encodeFindNodeReq(reqID uint32, target [32]byte, k int) []byte {
	if k < 1 {
		k = 1
	}
	if k > 256 {
		k = 256
	}
	body := make([]byte, 33)
	copy(body, target[:])
	body[32] = byte(k)
	return encodeRequest(reqID, OpFindNode, body)
}

func decodePacket(pkt []byte) (reqID uint32, op byte, body []byte, err error) {
	if len(pkt) < rpcHdr {
		return 0, 0, nil, errors.New("dht rpc: short packet")
	}
	if pkt[0] != rpcMagic0[0] || pkt[1] != rpcMagic1[0] || pkt[2] != rpcMagic2[0] || pkt[3] != rpcMagic3[0] {
		return 0, 0, nil, errors.New("dht rpc: bad magic")
	}
	if pkt[4] != rpcVer {
		return 0, 0, nil, fmt.Errorf("dht rpc: version %d", pkt[4])
	}
	op = pkt[5]
	reqID = binary.BigEndian.Uint32(pkt[6:10])
	bl := int(binary.BigEndian.Uint16(pkt[10:12]))
	if bl < 0 || rpcHdr+bl > len(pkt) {
		return 0, 0, nil, errors.New("dht rpc: bad length")
	}
	body = pkt[12 : 12+bl]
	return reqID, op, body, nil
}

type findNodeJSON struct {
	Nodes []discovery.RelayNode `json:"nodes"`
}

func encodeFindNodeResp(reqID uint32, nodes []discovery.RelayNode) ([]byte, error) {
	body, err := json.Marshal(findNodeJSON{Nodes: nodes})
	if err != nil {
		return nil, err
	}
	return encodeRequest(reqID, OpFindNode|opResp, body), nil
}

func decodeFindNodeResp(body []byte) ([]discovery.RelayNode, error) {
	var w findNodeJSON
	if err := json.Unmarshal(body, &w); err != nil {
		return nil, err
	}
	return w.Nodes, nil
}

type storeReqJSON struct {
	Key     string `json:"key"`
	TTLSec  uint32 `json:"ttlSec"`
	Payload string `json:"payload"`
}

type storeRespJSON struct {
	Ok bool `json:"ok"`
}

type getRespJSON struct {
	Found   bool   `json:"found"`
	Payload string `json:"payload,omitempty"`
}

func encodeStoreReq(reqID uint32, key [32]byte, ttlSec uint32, val []byte) ([]byte, error) {
	if len(val) > KVMaxValue {
		val = val[:KVMaxValue]
	}
	body, err := json.Marshal(storeReqJSON{
		Key:     hex.EncodeToString(key[:]),
		TTLSec:  ttlSec,
		Payload: hex.EncodeToString(val),
	})
	if err != nil {
		return nil, err
	}
	return encodeRequest(reqID, OpStore, body), nil
}

func decodeStoreReq(body []byte) (key [32]byte, ttlSec uint32, val []byte, err error) {
	var w storeReqJSON
	if err := json.Unmarshal(body, &w); err != nil {
		return key, 0, nil, err
	}
	kb, err := hex.DecodeString(w.Key)
	if err != nil || len(kb) != 32 {
		return key, 0, nil, errors.New("dht store: bad key")
	}
	copy(key[:], kb)
	ttlSec = w.TTLSec
	if w.Payload == "" {
		return key, ttlSec, nil, nil
	}
	val, err = hex.DecodeString(w.Payload)
	if err != nil {
		return key, ttlSec, nil, err
	}
	return key, ttlSec, val, nil
}

func encodeStoreResp(reqID uint32, ok bool) ([]byte, error) {
	body, err := json.Marshal(storeRespJSON{Ok: ok})
	if err != nil {
		return nil, err
	}
	return encodeRequest(reqID, OpStore|opResp, body), nil
}

func decodeStoreResp(body []byte) (bool, error) {
	var w storeRespJSON
	if err := json.Unmarshal(body, &w); err != nil {
		return false, err
	}
	return w.Ok, nil
}

func encodeGetReq(reqID uint32, key [32]byte) []byte {
	body := fmt.Sprintf(`{"key":"%x"}`, key)
	return encodeRequest(reqID, OpGet, []byte(body))
}

func decodeGetReq(body []byte) ([32]byte, error) {
	var w struct {
		Key string `json:"key"`
	}
	var key [32]byte
	if err := json.Unmarshal(body, &w); err != nil {
		return key, err
	}
	kb, err := hex.DecodeString(w.Key)
	if err != nil || len(kb) != 32 {
		return key, errors.New("dht get: bad key")
	}
	copy(key[:], kb)
	return key, nil
}

func encodeGetResp(reqID uint32, found bool, val []byte) ([]byte, error) {
	w := getRespJSON{Found: found}
	if found && len(val) > 0 {
		w.Payload = hex.EncodeToString(val)
	}
	body, err := json.Marshal(w)
	if err != nil {
		return nil, err
	}
	return encodeRequest(reqID, OpGet|opResp, body), nil
}

func decodeGetResp(body []byte) ([]byte, bool, error) {
	var w getRespJSON
	if err := json.Unmarshal(body, &w); err != nil {
		return nil, false, err
	}
	if !w.Found {
		return nil, false, nil
	}
	if w.Payload == "" {
		return nil, true, nil
	}
	val, err := hex.DecodeString(w.Payload)
	if err != nil {
		return nil, false, err
	}
	return val, true, nil
}
