package dexote

import (
	"encoding/hex"
	"testing"
)

func TestCrossVectors(t *testing.T) {
	serverPub, _ := hex.DecodeString("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
	ss1, _ := hex.DecodeString("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
	slot := int64(424242)

	cases := []struct {
		name string
		fn   func() []byte
		want string
	}{
		{"mask_stream", func() []byte { return maskStream(serverPub, slot) },
			"e4058dc0c47d82864ec7c404b5e258122cdbe89e63e766def2b9e90e636ba5b3"},
		{"k1_c2s", func() []byte { return hkdfKey(ss1, slotBytes(slot), infoC2S, keyLen) },
			"53d38ea8a1386990d38a442e92053482b7723a0e6fe7391d2e55a0a69f358a3c"},
		{"mac", func() []byte {
			data := append(append([]byte{}, serverPub...), slotBytes(slot)...)
			return mac(serverPub, data)
		}, "4c206cae518fa71e9175f7260fbc664d"},
	}
	for _, c := range cases {
		got := hex.EncodeToString(c.fn())
		if got != c.want {
			t.Errorf("%s = %s, want %s", c.name, got, c.want)
		}
	}
}

func TestCrossVectorsAEAD(t *testing.T) {
	key, _ := hex.DecodeString("0001020304050607000102030405060700010203040506070001020304050607")
	ad, _ := hex.DecodeString("aabbccdd")
	pt := []byte("dexote-aead-test")
	ct, err := seal(key, ad, pt)
	if err != nil {
		t.Fatal(err)
	}
	got := hex.EncodeToString(ct)
	want := "27a431df25a042a5f91c9f4a399bcfa9657c2bee272c95ae567e80cec3ee6bf1"
	if got != want {
		t.Errorf("aead_seal = %s, want %s", got, want)
	}
}

func TestCrossVectorsPoly(t *testing.T) {
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}
	p := NewPoly(secret, 99, "vec")
	want := []int{32247, 24580, 17521, 57491, 15039}
	for i := 0; i < 5; i++ {
		if got := p.IntRange(0, 65535); got != want[i] {
			t.Errorf("poly_seq[%d] = %d, want %d", i, got, want[i])
		}
	}
}
