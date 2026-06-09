package ipc

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"sync"
	"time"
)

type MessageType string

const (
	MsgTypeStatus     MessageType = "status"
	MsgTypeCommand    MessageType = "command"
	MsgTypeConnect    MessageType = "connect"
	MsgTypeDisconnect MessageType = "disconnect"
	MsgTypePing       MessageType = "ping"
	MsgTypePong       MessageType = "pong"
	MsgTypeQuit       MessageType = "quit"
)

type Message struct {
	Type      MessageType `json:"type"`
	Profile   string      `json:"profile,omitempty"`
	Connected bool        `json:"connected,omitempty"`
	Error     string      `json:"error,omitempty"`
	Timestamp int64       `json:"timestamp"`
}

type Client struct {
	conn net.Conn
	mu   sync.Mutex
}

type Server struct {
	listener net.Listener
	mu       sync.Mutex
	clients  map[net.Conn]bool
	handler  func(Message)
}

func NewClient(socketPath string) (*Client, error) {
	conn, err := net.Dial("unix", socketPath)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to socket: %w", err)
	}
	return &Client{conn: conn}, nil
}

func (c *Client) Send(msg Message) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	msg.Timestamp = time.Now().Unix()
	data, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	lenBuf := make([]byte, 4)
	len := uint32(len(data))
	lenBuf[0] = byte(len >> 24)
	lenBuf[1] = byte(len >> 16)
	lenBuf[2] = byte(len >> 8)
	lenBuf[3] = byte(len)

	if _, err := c.conn.Write(lenBuf); err != nil {
		return fmt.Errorf("failed to write length: %w", err)
	}

	if _, err := c.conn.Write(data); err != nil {
		return fmt.Errorf("failed to write message: %w", err)
	}
	return nil
}

func (c *Client) Receive() (Message, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	lenBuf := make([]byte, 4)
	if _, err := c.conn.Read(lenBuf); err != nil {
		return Message{}, fmt.Errorf("failed to read length: %w", err)
	}

	len := uint32(lenBuf[0])<<24 | uint32(lenBuf[1])<<16 | uint32(lenBuf[2])<<8 | uint32(lenBuf[3])

	data := make([]byte, len)
	if _, err := c.conn.Read(data); err != nil {
		return Message{}, fmt.Errorf("failed to read message: %w", err)
	}

	var msg Message
	if err := json.Unmarshal(data, &msg); err != nil {
		return Message{}, fmt.Errorf("failed to unmarshal message: %w", err)
	}

	return msg, nil
}

func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.Close()
}

func NewServer(socketPath string, handler func(Message)) (*Server, error) {
	if _, err := os.Stat(socketPath); err == nil {
		os.Remove(socketPath)
	}

	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, fmt.Errorf("failed to listen on socket: %w", err)
	}

	if err := os.Chmod(socketPath, 0777); err != nil {
		listener.Close()
		return nil, fmt.Errorf("failed to set socket permissions: %w", err)
	}

	return &Server{
		listener: listener,
		clients:  make(map[net.Conn]bool),
		handler:  handler,
	}, nil
}

func (s *Server) Start() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}

		s.mu.Lock()
		s.clients[conn] = true
		s.mu.Unlock()

		go s.handleConnection(conn)
	}
}

func (s *Server) handleConnection(conn net.Conn) {
	defer func() {
		conn.Close()
		s.mu.Lock()
		delete(s.clients, conn)
		s.mu.Unlock()
	}()

	for {
		lenBuf := make([]byte, 4)
		if _, err := conn.Read(lenBuf); err != nil {
			return
		}

		len := uint32(lenBuf[0])<<24 | uint32(lenBuf[1])<<16 | uint32(lenBuf[2])<<8 | uint32(lenBuf[3])

		data := make([]byte, len)
		if _, err := conn.Read(data); err != nil {
			return
		}

		var msg Message
		if err := json.Unmarshal(data, &msg); err != nil {
			continue
		}

		if s.handler != nil {
			s.handler(msg)
		}
	}
}

func (s *Server) Broadcast(msg Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	msg.Timestamp = time.Now().Unix()
	data, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	lenBuf := make([]byte, 4)
	len := uint32(len(data))
	lenBuf[0] = byte(len >> 24)
	lenBuf[1] = byte(len >> 16)
	lenBuf[2] = byte(len >> 8)
	lenBuf[3] = byte(len)

	for conn := range s.clients {
		if _, err := conn.Write(lenBuf); err != nil {
			delete(s.clients, conn)
			conn.Close()
			continue
		}
		if _, err := conn.Write(data); err != nil {
			delete(s.clients, conn)
			conn.Close()
			continue
		}
	}

	return nil
}

func (s *Server) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	for conn := range s.clients {
		conn.Close()
	}

	return s.listener.Close()
}

func GetSocketPath() string {
	return "/tmp/volter-ipc.sock"
}
