package main

import (
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"

	"dev.c0redev.volter/internal/dexote"
	"dev.c0redev.volter/internal/obfuscate"
)

func main() {
	if len(os.Args) < 4 {
		fmt.Fprintln(os.Stderr, "usage: dexote-e2e-server <scalarB64> <slot> <token>")
		os.Exit(2)
	}
	scalar, err := base64.StdEncoding.DecodeString(os.Args[1])
	if err != nil || len(scalar) != 32 {
		fmt.Fprintln(os.Stderr, "bad scalar")
		os.Exit(2)
	}
	pub, err := pubOf(scalar)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pub:", err)
		os.Exit(2)
	}
	slot, _ := strconv.ParseInt(os.Args[2], 10, 64)
	token := os.Args[3]

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		fmt.Fprintln(os.Stderr, "listen:", err)
		os.Exit(1)
	}
	fmt.Printf("READY %d\n", ln.Addr().(*net.TCPAddr).Port)
	os.Stdout.Sync()

	conn, err := ln.Accept()
	if err != nil {
		fmt.Fprintln(os.Stderr, "accept:", err)
		os.Exit(1)
	}
	defer conn.Close()

	keys, payload, err := dexote.ServerHandshake(conn, scalar, pub, slot, []byte{9, 9}, dexote.NewMemReplayCache())
	if err != nil {
		fmt.Fprintln(os.Stderr, "handshake:", err)
		os.Exit(1)
	}
	if payload.Token != token {
		fmt.Fprintf(os.Stderr, "token mismatch: %q\n", payload.Token)
		os.Exit(1)
	}
	rw := obfuscate.WrapAEAD(conn, keys,
		dexote.NewPoly(keys.Secret, slot, "s"),
		dexote.NewPoly(keys.Secret, slot, "c"), 64)
	io.Copy(rw, rw)
}

func pubOf(scalar []byte) ([]byte, error) {
	return dexote.PubFromScalar(scalar)
}
