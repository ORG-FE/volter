package dht

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"syscall"
	"time"

	"dev.c0redev.volter/internal/discovery"
	"dev.c0redev.volter/internal/sockprotect"
)

func ListenRPCUDP(addr string, secret string, tab *Table, kv *KVStore) (*net.UDPConn, error) {
	if tab == nil {
		tab = DefaultTable()
	}
	if kv == nil {
		kv = DefaultKVStore()
	}
	lc := net.ListenConfig{}
	if sockprotect.Protect != nil {
		lc.Control = func(network, address string, c syscall.RawConn) error {
			var err error
			e := c.Control(func(fd uintptr) {
				err = sockprotect.Protect(fd)
			})
			if e != nil {
				return e
			}
			return err
		}
	}
	pc, err := lc.ListenPacket(context.Background(), "udp", addr)
	if err != nil {
		return nil, err
	}
	uc, ok := pc.(*net.UDPConn)
	if !ok {
		_ = pc.Close()
		return nil, errors.New("dht rpc: expected udp conn")
	}
	go serveRPCUDP(uc, secret, tab, kv)
	return uc, nil
}

func serveRPCUDP(uc *net.UDPConn, secret string, tab *Table, kv *KVStore) {
	buf := make([]byte, 65536)
	for {
		n, raddr, err := uc.ReadFromUDP(buf)
		if err != nil {
			return
		}
		pkt := buf[:n]
		pkt, ok := verifyMAC(secret, pkt)
		if !ok {
			continue
		}
		reqID, op, body, err := decodePacket(pkt)
		if err != nil {
			continue
		}
		if op&opResp != 0 {
			continue
		}
		switch op {
		case OpPing:
			resp := encodeRequest(reqID, OpPing|opResp, nil)
			resp = appendMAC(secret, resp)
			_, _ = uc.WriteToUDP(resp, raddr)
		case OpFindNode:
			if len(body) < 33 {
				continue
			}
			var target [32]byte
			copy(target[:], body)
			k := int(body[32])
			nodes := tab.NearestTo(target, k)
			out, err := encodeFindNodeResp(reqID, nodes)
			if err != nil {
				continue
			}
			out = appendMAC(secret, out)
			_, _ = uc.WriteToUDP(out, raddr)
		case OpStore:
			if kv == nil {
				continue
			}
			key, ttlSec, val, err := decodeStoreReq(body)
			if err != nil {
				continue
			}
			ttl := time.Duration(ttlSec) * time.Second
			ok := kv.Put(key, ttl, val)
			out, err := encodeStoreResp(reqID, ok)
			if err != nil {
				continue
			}
			out = appendMAC(secret, out)
			_, _ = uc.WriteToUDP(out, raddr)
		case OpGet:
			if kv == nil {
				continue
			}
			key, err := decodeGetReq(body)
			if err != nil {
				continue
			}
			val, found := kv.Get(key)
			out, err := encodeGetResp(reqID, found, val)
			if err != nil {
				continue
			}
			out = appendMAC(secret, out)
			_, _ = uc.WriteToUDP(out, raddr)
		default:
			continue
		}
	}
}

func udpExchange(ctx context.Context, addr, secret string, req []byte, wantRespOp byte) ([]byte, error) {
	if len(req) < rpcHdr {
		return nil, errors.New("dht rpc: bad req")
	}
	wantID := binary.BigEndian.Uint32(req[6:10])
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{Timeout: 5 * time.Second}
	if sockprotect.Protect != nil {
		d.Control = func(network, address string, c syscall.RawConn) error {
			var err error
			e := c.Control(func(fd uintptr) {
				err = sockprotect.Protect(fd)
			})
			if e != nil {
				return e
			}
			return err
		}
	}
	conn, err := d.DialContext(ctx, "udp", udpAddr.String())
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	uc, uok := conn.(*net.UDPConn)
	if !uok {
		return nil, errors.New("dht rpc: dial not udp")
	}
	req = appendMAC(secret, append([]byte(nil), req...))
	deadline, okDL := ctx.Deadline()
	if !okDL {
		deadline = time.Now().Add(8 * time.Second)
	}
	_ = uc.SetDeadline(deadline)
	if _, err := uc.Write(req); err != nil {
		return nil, err
	}
	readBuf := make([]byte, 65536)
	n, err := uc.Read(readBuf)
	if err != nil {
		return nil, err
	}
	pkt := readBuf[:n]
	pkt, macOK := verifyMAC(secret, pkt)
	if !macOK {
		return nil, errors.New("dht rpc: bad mac")
	}
	rid, op, body, err := decodePacket(pkt)
	if err != nil {
		return nil, err
	}
	if rid != wantID || op != wantRespOp {
		return nil, fmt.Errorf("dht rpc: bad response op=%x want=%x", op, wantRespOp)
	}
	return body, nil
}

func UDPPing(ctx context.Context, addr, secret string) error {
	var reqID uint32
	if err := binary.Read(rand.Reader, binary.BigEndian, &reqID); err != nil {
		return err
	}
	req := encodePingReq(reqID)
	_, err := udpExchange(ctx, addr, secret, req, OpPing|opResp)
	return err
}

func UDPFindNode(ctx context.Context, addr string, secret string, target [32]byte, k int) ([]discovery.RelayNode, error) {
	if k < 1 {
		k = 16
	}
	if k > 256 {
		k = 256
	}
	var reqID uint32
	if err := binary.Read(rand.Reader, binary.BigEndian, &reqID); err != nil {
		return nil, err
	}
	req := encodeFindNodeReq(reqID, target, k)
	body, err := udpExchange(ctx, addr, secret, req, OpFindNode|opResp)
	if err != nil {
		return nil, err
	}
	return decodeFindNodeResp(body)
}

func UDPStore(ctx context.Context, addr, secret string, key [32]byte, ttlSec uint32, val []byte) (bool, error) {
	var reqID uint32
	if err := binary.Read(rand.Reader, binary.BigEndian, &reqID); err != nil {
		return false, err
	}
	req, err := encodeStoreReq(reqID, key, ttlSec, val)
	if err != nil {
		return false, err
	}
	body, err := udpExchange(ctx, addr, secret, req, OpStore|opResp)
	if err != nil {
		return false, err
	}
	return decodeStoreResp(body)
}

func UDPGet(ctx context.Context, addr, secret string, key [32]byte) ([]byte, bool, error) {
	var reqID uint32
	if err := binary.Read(rand.Reader, binary.BigEndian, &reqID); err != nil {
		return nil, false, err
	}
	req := encodeGetReq(reqID, key)
	body, err := udpExchange(ctx, addr, secret, req, OpGet|opResp)
	if err != nil {
		return nil, false, err
	}
	return decodeGetResp(body)
}
