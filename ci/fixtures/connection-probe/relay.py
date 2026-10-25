"""A plain TCP relay: like a SOCKS, residential or booster proxy, it ends the player's TCP connection itself."""
import asyncio
import sys

TARGET = (sys.argv[1], int(sys.argv[2]))


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except ConnectionError:
        pass
    finally:
        writer.close()


async def session(client_reader, client_writer):
    server_reader, server_writer = await asyncio.open_connection(*TARGET)
    await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer))


async def main():
    server = await asyncio.start_server(session, '0.0.0.0', 25577)
    async with server:
        await server.serve_forever()

asyncio.run(main())
