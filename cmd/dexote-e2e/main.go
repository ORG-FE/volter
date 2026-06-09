package main

import (
	"fmt"
	"io"
	"net"
	"os"
	"strconv"

	"dev.c0redev.volter/internal/dexote"
	"dev.c0redev.volter/internal/obfuscate"
)

func main() {
	if len(os.Args) < 5 {
		fmt.Fprintln(os.Stderr, "usage: dexote-e2e <addr> <serverPubB64> <slot> <token>")
		os.Exit(2)
	}
	addr := os.Args[1]
	pub, err := dexote.DecodePub(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, "decode pub:", err)
		os.Exit(2)
	}
	slot, _ := strconv.ParseInt(os.Args[3], 10, 64)
	token := os.Args[4]

	role := byte(2)
	var opts []byte
	if len(os.Args) >= 6 {
		r, _ := strconv.Atoi(os.Args[5])
		role = byte(r)
	}
	if role == 1 && len(os.Args) >= 7 {
		ch, _ := strconv.Atoi(os.Args[6])
		opts = []byte{byte(ch)}
	}

	conn, err := net.Dial("tcp", addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "dial:", err)
		os.Exit(1)
	}
	defer conn.Close()

	keys, _, err := dexote.ClientHandshake(conn, pub, slot, dexote.ClientHelloPayload{Role: role, Token: token, Opts: opts})
	if err != nil {
		fmt.Fprintln(os.Stderr, "handshake:", err)
		os.Exit(1)
	}
	w := obfuscate.WrapAEAD(conn, keys,
		dexote.NewPoly(keys.Secret, slot, "tx"),
		dexote.NewPoly(keys.Secret, slot, "rx"), 64)

	msg := []byte("dexote-ping-0123456789")
	if _, err := w.Write(msg); err != nil {
		fmt.Fprintln(os.Stderr, "write:", err)
		os.Exit(1)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(w, got); err != nil {
		fmt.Fprintln(os.Stderr, "read:", err)
		os.Exit(1)
	}
	if string(got) != string(msg) {
		fmt.Fprintf(os.Stderr, "echo mismatch: %q\n", got)
		os.Exit(1)
	}
	fmt.Println("OK")
}
