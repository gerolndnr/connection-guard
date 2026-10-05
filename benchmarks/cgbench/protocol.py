import ipaddress
import json
import socket
import struct
import time


def varint(number):
    output = bytearray()
    while True:
        value = number & 127; number >>= 7
        output.append(value | (128 if number else 0))
        if not number:
            return bytes(output)


def text(value):
    value = value.encode('utf-8')
    return varint(len(value)) + value


def packet(kind, content):
    content = varint(kind) + content
    return varint(len(content)) + content


def exact(connection, count):
    out = bytearray()
    while len(out) < count:
        part = connection.recv(count - len(out))
        if not part:
            raise EOFError('Incomplete packet')
        out.extend(part)
    return bytes(out)


def read_varint(connection):
    output = 0
    for shift in range(0, 35, 7):
        value = exact(connection, 1)[0]; output |= (value & 127) << shift
        if value < 128:
            return output
    raise ValueError('Invalid VarInt')


def proxy_v2(ip, source_port, destination_port):
    address = ipaddress.ip_address(ip)
    destination = ipaddress.ip_address('127.0.0.1' if address.version == 4 else '::1')
    content = address.packed + destination.packed + struct.pack('>HH', source_port, destination_port)
    return b'\r\n\r\n\x00\r\nQUIT\n' + bytes([0x21, 0x11 if address.version == 4 else 0x21]) + struct.pack('>H', len(content)) + content


def login(port, ip, name, timeout=3, proxy_header=True):
    # The destination is always the owned loopback proxy; the subject IP is a fixture header.
    started = time.perf_counter_ns()
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=timeout) as connection:
            if proxy_header:
                connection.sendall(proxy_v2(ip, connection.getsockname()[1], port))
            connection.sendall(packet(0, varint(760) + text('localhost') + struct.pack('>H', port) + varint(2)))
            connection.sendall(packet(0, text(name) + b'\x00\x00'))
            count = read_varint(connection)
            if not 1 <= count <= 65536:
                raise ValueError('Unexpected packet length')
            content = exact(connection, count)
            if content[0] == 2:
                outcome, reason = 'ALLOW', 'login_success_packet'
            elif content[0] == 0:
                outcome, reason = 'DENY', 'login_disconnect_packet'
            else:
                outcome, reason = 'PROTOCOL_ERROR', 'unexpected_packet_' + str(content[0])
    except (socket.timeout, TimeoutError):
        outcome, reason = 'TIMEOUT', 'harness_deadline'
    except (OSError, EOFError, ValueError) as error:
        outcome, reason = 'PROTOCOL_ERROR', type(error).__name__ + ':' + str(error)[:120]
    return dict(outcome=outcome, reason=reason, duration_ns=time.perf_counter_ns() - started)
