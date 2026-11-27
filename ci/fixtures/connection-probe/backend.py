"""Minimal offline 1.21.11 (protocol 774) backend for Velocity with forwarding "none".

Not a Minecraft server: it completes login and configuration, sends a Login (play) packet Velocity can parse,
then a keep-alive every KEEPALIVE_S seconds (Paper sends one every 15 s) so that Velocity measures the player's ping.
"""
import asyncio
import os
import struct
import sys
import time

sys.path.insert(0, os.environ.get('BENCH_HARNESS', '/bench'))
from bench.mcclient import Connection, read_string, read_varint_bytes, string, varint  # noqa: E402

KEEPALIVE_S = float(os.environ.get('KEEPALIVE_S', '15'))


def login_play():
    return (struct.pack('>i', 1) + b'\x00' + varint(1) + string('minecraft:overworld') + varint(20) + varint(4) + varint(4)
            + b'\x00' + b'\x01' + b'\x00' + varint(0) + string('minecraft:overworld') + struct.pack('>q', 0)
            + bytes([1]) + bytes([0xFF]) + b'\x00' + b'\x01' + b'\x00' + varint(0) + varint(63) + b'\x00')


async def session(reader, writer):
    connection = Connection(reader, writer)
    try:
        packet_id, data = await connection.read_packet()          # handshake
        _, data = await connection.read_packet()                  # login start
        name, offset = read_string(data)
        uuid = data[offset:offset + 16]
        connection.send(0x02, uuid + string(name) + varint(0))    # login success, no properties
        await writer.drain()
        while (await connection.read_packet())[0] != 0x03:        # login acknowledged
            pass
        connection.send(0x03)                                     # finish configuration
        await writer.drain()
        while (await connection.read_packet())[0] != 0x03:        # acknowledge finish configuration
            pass
        connection.send(0x30, login_play())
        await writer.drain()

        async def keepalive():
            while True:
                await asyncio.sleep(KEEPALIVE_S)
                connection.send(0x2B, struct.pack('>q', int(time.time() * 1000)))
                await writer.drain()
        task = asyncio.create_task(keepalive())
        try:
            while True:
                await connection.read_packet()
        finally:
            task.cancel()
    except (asyncio.IncompleteReadError, ConnectionError, ValueError):
        pass
    finally:
        writer.close()


async def main():
    server = await asyncio.start_server(session, '127.0.0.1', 25566)
    print('backend listening on 127.0.0.1:25566', flush=True)
    async with server:
        await server.serve_forever()

asyncio.run(main())
