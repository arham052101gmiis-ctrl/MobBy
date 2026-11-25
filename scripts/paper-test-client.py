"""Minimal loopback, offline-mode Minecraft 26.3 client for the distant-player fixture."""
import io
import socket
import struct
import sys
import uuid


def varint(value):
    data = bytearray()
    while value >= 128:
        data.append(value & 127 | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def read_int(stream):
    value = 0
    for shift in range(0, 35, 7):
        byte = stream.read(1)
        if not byte:
            raise EOFError("Test server closed connection")
        value |= (byte[0] & 127) << shift
        if byte[0] < 128:
            return value
    raise ValueError("Invalid VarInt")


def string(value):
    data = value.encode()
    return varint(len(data)) + data


def run(port):
    with socket.create_connection(("127.0.0.1", port), timeout=30) as sock, sock.makefile("rb") as stream:
        def send(kind, data=b""):
            packet = varint(kind) + data
            sock.sendall(varint(len(packet)) + packet)

        send(0, varint(777) + string("localhost") + struct.pack(">H", port) + varint(2))
        send(0, string("MobByObserver") + uuid.uuid4().bytes)
        state = "login"
        while True:
            packet = io.BytesIO(stream.read(read_int(stream)))
            kind = read_int(packet)
            if state == "login":
                if kind != 2:
                    raise ValueError(f"Unexpected login packet {kind}")
                send(3)
                state = "configuration"
            elif state == "configuration":
                if kind == 15:
                    send(7, b"\x00")
                elif kind == 3:
                    send(3)
                    state = "play"
                elif kind in (4, 5):
                    send(kind, packet.read())
                elif kind == 2:
                    raise ValueError("Disconnected during configuration")
            elif kind == 45:
                send(28, packet.read())
            elif kind == 73:
                teleport = read_int(packet)
                position = packet.read(24)
                packet.read(24)  # Velocity is omitted from the acknowledgement.
                rotation = packet.read(8)
                send(0, varint(teleport) + position + rotation)
                send(44)


if __name__ == "__main__":
    run(int(sys.argv[1]))
