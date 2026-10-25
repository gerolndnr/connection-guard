"""Inside the server container: fake backend, then Velocity with Connection Guard and the experimental probe on.

First start generates velocity.toml and the plugin config; both are patched, then Velocity runs in the foreground.
Local-test rules: cloud off, API lookups off (LOCAL_ONLY), bStats off.
"""
import os
import re
import shutil
import subprocess
import sys
import time

import yaml

WORK = '/work'
os.makedirs(f'{WORK}/plugins/bStats', exist_ok=True)
shutil.copy('/velocity.jar', f'{WORK}/velocity.jar')
shutil.copy('/plugin.jar', f'{WORK}/plugins/connection-guard.jar')
open(f'{WORK}/plugins/bStats/config.txt', 'w').write(
    'enabled=false\nserver-uuid=00000000-0000-4000-8000-000000000000\nlog-errors=false\nlog-sent-data=false\nlog-response-status-text=false\n')
env = dict(os.environ, CONNECTIONGUARD_CLOUD='false')
subprocess.Popen([sys.executable, '/fixture/backend.py'], env=env)


def run_until_done():
    process = subprocess.Popen(['java', '-Xmx512M', '-jar', 'velocity.jar'], cwd=WORK, env=env, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in process.stdout:
        print('[first start] ' + line, end='', flush=True)
        if re.search(r'Done \(', line):
            process.stdin.write('shutdown\n'); process.stdin.flush()
    process.wait()


run_until_done()
toml = open(f'{WORK}/velocity.toml').read()
for pattern, value in {r'^bind = .*$': 'bind = "0.0.0.0:25577"', r'^online-mode = .*$': 'online-mode = false',
                       r'^force-key-authentication = .*$': 'force-key-authentication = false',
                       r'^player-info-forwarding-mode = .*$': 'player-info-forwarding-mode = "none"',
                       r'^login-ratelimit = .*$': 'login-ratelimit = 0'}.items():
    toml, count = re.subn(pattern, value, toml, flags=re.M)
    assert count == 1, pattern
toml = re.sub(r'(?ms)^\[servers\].*?(?=^\[forced-hosts\])', '[servers]\nlobby = "127.0.0.1:25566"\ntry = ["lobby"]\n\n', toml)
toml = re.sub(r'(?ms)^\[forced-hosts\].*?(?=^\[advanced\])', '[forced-hosts]\n\n', toml)
open(f'{WORK}/velocity.toml', 'w').write(toml)

config_dir = next(os.path.join(f'{WORK}/plugins', d) for d in os.listdir(f'{WORK}/plugins')
                  if os.path.isfile(os.path.join(f'{WORK}/plugins', d, 'config.yml')))
path = os.path.join(config_dir, 'config.yml')
config = yaml.safe_load(open(path))
config.setdefault('lookup', {})['api-policy'] = 'LOCAL_ONLY'
config['experimental'] = {'connection-probe': {'enabled': True, 'gap-threshold-ms': int(os.environ.get('GAP_THRESHOLD_MS', '40')),
                                               'journal': True, 'reverse-dns': True}}
yaml.safe_dump(config, open(path, 'w'), sort_keys=False)
print(f'patched {path}', flush=True)
os.chdir(WORK)
os.execvpe('java', ['java', '-Xmx512M', '-jar', 'velocity.jar'], env)
