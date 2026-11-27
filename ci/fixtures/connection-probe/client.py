"""One join through the bench client, kept in the world for OBSERVE_S seconds (answers every keep-alive)."""
import asyncio
import json
import os
import sys

sys.path.insert(0, os.environ.get('BENCH_HARNESS', '/bench'))
from bench.mcclient import admit  # noqa: E402

host, name = sys.argv[1], sys.argv[2]
result = asyncio.run(admit(25577, None, name, host=host, proxy=False, observe_s=float(os.environ.get('OBSERVE_S', '75')),
                           deadline_s=float(os.environ.get('OBSERVE_S', '75')) + 30))
print(json.dumps({k: result[k] for k in ('name', 'outcome', 'reason')}), flush=True)
