"""Unmodified plugin JARs, an owned Velocity proxy, and an owned HTTP provider fixture."""
import concurrent.futures
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
import zipfile
import platform
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from .artifacts import inspect_jar
from .model import fingerprint, require, sha
from .protocol import login

RUNTIME_SHA = 'fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e'


class ArtifactIncompatible(ValueError):
    pass


def resources(pid):
    """Observed whole-proxy RSS and coarse process CPU time; never plugin-only allocation."""
    try:
        value = subprocess.run(['ps', '-p', str(pid), '-o', 'rss=,time='], capture_output=True,
                               text=True, check=True, timeout=2).stdout.split()
        parts = value[1].split(':')
        seconds = sum(float(part) * 60 ** index for index, part in enumerate(reversed(parts)))
        return dict(rss_bytes=int(value[0]) * 1024, cpu_seconds=seconds)
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return dict(rss_bytes=None, cpu_seconds=None)


def hardware():
    value = dict(cpu_count=os.cpu_count(), machine=platform.machine())
    if platform.system() == 'Darwin':
        for field, name in [('cpu_model', 'machdep.cpu.brand_string'), ('physical_memory_bytes', 'hw.memsize')]:
            try:
                text = subprocess.run(['sysctl', '-n', name], capture_output=True, text=True, check=True, timeout=2).stdout.strip()
                value[field] = int(text) if field.endswith('bytes') else text
            except (OSError, ValueError, subprocess.SubprocessError):
                value[field] = None
    else:
        value['cpu_model'] = platform.processor() or None
        try:
            value['physical_memory_bytes'] = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
        except (OSError, ValueError):
            value['physical_memory_bytes'] = None
    return value


class ProviderFixture:
    def __init__(self, behavior):
        self.calls = 0
        self.lock = threading.Lock()
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                with fixture.lock:
                    fixture.calls += 1
                positive = behavior not in {'negative', 'manual_deny', 'expiry'}
                status = 503 if behavior.startswith('503') else 429 if behavior.startswith('429') else 200
                if behavior.startswith('timeout'):
                    time.sleep(.8)
                elif behavior in {'slow_positive', 'shared_positive', 'unique_positive'}:
                    time.sleep(.05)
                body = json.dumps({'data': {'isVpn': positive, 'vpnProvider': 'Owned synthetic source'},
                                   'countryCode': 'GB', 'countryName': 'United Kingdom', 'isVpn': positive,
                                   'isHosting': False, 'isProxy': False, 'asName': 'Owned fixture', 'asn': 'AS0'}).encode()
                if behavior.startswith('malformed'):
                    body = b'{invalid-json'
                elif behavior.startswith('missing'):
                    body = b'{"data":{}}'
                self.send_response(status)
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
            def log_message(self, *args):
                pass
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)


def cg_config(jar, fixture, endpoint, geo_db=None):
    import yaml
    with zipfile.ZipFile(jar) as archive:
        config = yaml.safe_load(archive.read('config.yml'))
        messages = archive.read('translation/en.yml')
    for provider in config['provider']['vpn'].values():
        provider['enabled'] = False
    config['provider']['vpn']['custom'].update(enabled=True, **{'request-url': endpoint + '/%IP%', 'request-header': []})
    config['provider']['geo']['service'] = 'Disabled'
    config['provider']['cache']['type'] = 'SQLite' if fixture == 'warm_cache' else 'Disabled'
    config['cloud']['enabled'] = False
    config['operation']['mode'] = 'ENFORCE'
    config['failure-policy'] = {'vpn': 'CLOSED' if fixture.endswith('closed') else 'OPEN', 'geo': 'OPEN'}
    config['lookup'].update({'deadline-ms': 750, 'http-timeout-ms': 500, 'workers': 4, 'queue-capacity': 32,
                            'max-inflight': 64, 'circuit': {'failures': 1000, 'pause-ms': 50}})
    for kind in ['vpn', 'geo']:
        behavior = config['behavior'][kind]
        behavior['exemptions'] = []
        behavior['use-permission-exemption'] = False
        behavior['notify-staff'] = False
        behavior['execute-command']['enabled'] = False
        behavior['send-webhook']['enabled'] = False
    files = {'translation/en.yml': messages}
    if fixture.startswith('geo_'):
        require(geo_db is not None, 'Native CG geo requires the explicit MaxMind test database')
        for provider in config['provider']['vpn'].values():
            provider['enabled'] = False
        config['provider']['geo']['service'] = 'Local'
        config['provider']['local'] = {'update-hours': 0, 'sources': [dict(id='geo', type='GEO',
            source='MaxMind public test data', license='MIT', notice='Copyright MaxMind Inc.', **{'max-age-hours': 87600})]}
        config['behavior']['geo']['type'] = 'BLACKLIST' if fixture == 'geo_block_gb' else 'WHITELIST'
        config['behavior']['geo']['list'] = [] if fixture == 'geo_empty_whitelist' else ['GB']
        files['local-data/inbox/GeoIP2-Country-Test.mmdb'] = Path(geo_db).read_bytes()
    files['config.yml'] = yaml.safe_dump(config, sort_keys=False).encode()
    return files


def georestrict_config(fixture, endpoint):
    import yaml
    geo = fixture.startswith('geo_')
    config = dict(configVersion=5, gatewayUrl=endpoint, gatewayToken='', updateCheck=False,
                  connectionTimeoutMs=500, lookupThreads=4, blockOnLookupFailure=fixture.endswith('closed'),
                  cacheTtlDays=1, maxCacheEntries=1000, countryMode='DISABLED', countries=[], asnMode='DISABLED', asns=[],
                  vpnCheckEnabled=not geo, vpnKeywords=[], floodgate=dict(enabled=False), discord=dict(webhook=''))
    if geo:
        config['countryMode'] = 'BLOCKLIST' if fixture == 'geo_block_gb' else 'ALLOWLIST'
        config['countries'] = [] if fixture == 'geo_empty_whitelist' else ['GB']
    return {'config.yml': yaml.safe_dump(config, sort_keys=False).encode()}


def security_policy(work, java, runtime, harness=None):
    def quoted(path):
        return str(path).replace('\\', '\\\\').replace('"', '\\"')
    # Java 21 only. External service requests are not part of controlled experiments.
    paths = [str(work) + '/-', str(Path(java).resolve().parents[1]) + '/-', str(runtime), '/usr/lib/-', '/System/Library/-',
             '/etc/localtime', '/etc/timezone', '/etc/os-release', '/dev/random', '/dev/urandom', '/proc/-']
    if harness:
        paths.append(str(harness) + '/-')
    paths.extend(str(path) for path in [work, *work.parents])  # Directory metadata for local-store symlink checks.
    permissions = ['permission java.io.FilePermission "' + quoted(path) + '", "read";' for path in paths]
    permissions += ['permission java.io.FilePermission "' + quoted(str(work) + '/-') + '", "read,write,delete";',
                    'permission java.io.FilePermission "plugins/-", "read,write,delete";',
                    'permission java.util.PropertyPermission "*", "read,write";',
                    'permission java.lang.RuntimePermission "*";', 'permission java.lang.reflect.ReflectPermission "*";',
                    'permission java.security.SecurityPermission "*";', 'permission javax.management.MBeanServerPermission "*";',
                    'permission java.net.NetPermission "*";',
                    'permission java.util.logging.LoggingPermission "control";',
                    'permission java.lang.management.ManagementPermission "monitor";',
                    'permission jdk.jfr.FlightRecorderPermission "registerEvent";',
                    'permission jdk.jfr.FlightRecorderPermission "accessFlightRecorder";',
                    'permission javax.management.MBeanPermission "*", "*";',
                    'permission java.net.SocketPermission "*", "resolve";',
                    'permission java.net.URLPermission "http://127.0.0.1:*", "GET:*";',
                    'permission java.net.URLPermission "http://127.0.0.1:*/-", "GET:*";',
                    'permission java.net.SocketPermission "127.0.0.1:*", "connect,listen,accept,resolve";',
                    'permission java.net.SocketPermission "localhost:*", "connect,listen,accept,resolve";',
                    'permission java.net.SocketPermission "[::1]:*", "connect,listen,accept,resolve";']
    return 'grant {\n' + '\n'.join(permissions) + '\n};\n'


class Runtime:
    def __init__(self, directory, java, runtime, plugin, files, assets=None, harness=None, proxy_header=True, sqlite=None):
        self.directory = directory
        directory.mkdir(mode=0o700)
        shutil.copyfile(runtime, directory / 'velocity.jar')
        runtime = directory / 'velocity.jar'
        plugin_id = inspect_jar(plugin)['descriptor']['id']
        (directory / 'plugins').mkdir()
        shutil.copyfile(plugin, directory / 'plugins/plugin-under-test.jar')
        data = directory / 'plugins' / plugin_id
        data.mkdir()
        for relative, body in files.items():
            path = data / relative
            require(not Path(relative).is_absolute() and '..' not in Path(relative).parts, 'Invalid seed filename')
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(body)
        if assets:
            target = data / 'lib'; target.mkdir(exist_ok=True)
            for path in Path(assets).rglob('*.jar'):
                require(not path.is_symlink() and path.stat().st_size <= 64 * 1024 * 1024, 'Invalid local dependency')
                relative = path.relative_to(assets); destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(path, destination)
        (directory / 'plugins/bStats').mkdir()
        (directory / 'plugins/bStats/config.txt').write_text('enabled=false\nserver-uuid=00000000-0000-4000-8000-000000000001\n')
        (directory / 'plugins/bStats/config.yml').write_text('enabled: false\nserverUuid: 00000000-0000-4000-8000-000000000001\n')
        with zipfile.ZipFile(runtime) as archive:
            config = archive.read('default-velocity.toml').decode()
        config, count = re.subn(r'^bind = "[^"]+"$', 'bind = "127.0.0.1:0"', config, flags=re.M)
        require(count == 1, 'Inspect changed runtime defaults')
        config, forwarding_count = re.subn(r'^player-info-forwarding-mode = "(?:modern|NONE|none)"$', 'player-info-forwarding-mode = "NONE"', config, flags=re.M)
        require(forwarding_count == 1, 'Inspect runtime forwarding setting')
        for old, new in [('online-mode = true', 'online-mode = false'), ('force-key-authentication = true', 'force-key-authentication = false'),
                         ('login-ratelimit = 3000', 'login-ratelimit = 0'), ('compression-threshold = 256', 'compression-threshold = -1'),
                         ('haproxy-protocol = false', 'haproxy-protocol = true')]:
            require(old in config, 'Inspect runtime setting: ' + old)
            config = config.replace(old, new)
        if not proxy_header:
            config = config.replace('haproxy-protocol = true', 'haproxy-protocol = false')
        self.reserved = socket.socket()
        try:
            self.reserved.bind(('127.0.0.1', 0))
        except OSError:
            self.reserved.close(); raise
        config = re.sub(r'^(lobby|factions|minigames) = "[^"]+"$',
                        lambda match: match[1] + ' = "127.0.0.1:' + str(self.reserved.getsockname()[1]) + '"', config, flags=re.M)
        (directory / 'velocity.toml').write_text(config)
        temporary = directory / 'tmp'; temporary.mkdir()
        with zipfile.ZipFile(runtime) as archive:
            debug_logging = archive.read('log4j2.xml').decode()
        (directory / 'log4j2.xml').write_text(debug_logging)
        policy = directory / 'benchmark.policy'; policy.write_text(security_policy(directory, java, runtime, harness))
        environment = {key: os.environ[key] for key in ['PATH', 'JAVA_HOME', 'LANG'] if key in os.environ}
        environment.update(CONNECTIONGUARD_CLOUD='false', TMPDIR=str(temporary))
        self.lines = queue.Queue(); self.transcript = []
        classpath = [str(harness), str(runtime)]
        if sqlite:
            shutil.copyfile(sqlite, directory / 'sqlite.jar'); classpath.append(str(directory / 'sqlite.jar'))
        try:
            self.process = subprocess.Popen([str(java), '-Xms64m', '-Xmx256m', '-Dio.netty.eventLoopThreads=2',
                    '-Dio.netty.transport.noNative=true',
                    '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Djava.io.tmpdir=' + str(temporary),
                    '-Duser.dir=' + str(directory),
                    '-Dlog4j.configurationFile=' + str(directory / 'log4j2.xml'),
                    '-Dbench.audit.path=' + str(directory / 'permission-audit.log'),
                    '-Djava.security.manager=bench.fixture.LoopbackSecurityManager', '-Djava.security.policy==' + str(policy),
                    '-cp', os.pathsep.join(classpath), 'com.velocitypowered.proxy.Velocity'],
                    cwd=directory, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, bufsize=1)
        except OSError:
            self.reserved.close(); raise
        def consume():
            for line in self.process.stdout:
                line = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', line)
                self.transcript.append(line); self.lines.put(line)
            self.lines.put(None)
        self.reader = threading.Thread(target=consume, daemon=True)
        self.reader.start()
    def wait(self, marker, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = self.lines.get(timeout=.1)
            except queue.Empty:
                continue
            require(value is not None, 'Owned runtime exited before readiness')
            require(not re.search("Couldn't pass ProxyInitializeEvent|Unable to initialize plugin", value), 'Owned runtime/adapter initialization failed')
            if marker in value:
                return value
        raise TimeoutError('Owned runtime did not produce expected marker')
    def ready(self):
        self.wait('Done (')
        require(not re.search('Unable to initialize plugin|Error loading plugin|NoClassDefFoundError', ''.join(self.transcript)), 'Plugin did not activate')
        matches = [re.search(r'127\.0\.0\.1:(\d+)', line) for line in self.transcript if 'Listening on' in line]
        require(matches and matches[0], 'Owned loopback port was not observed')
        self.port = int(matches[0][1])
    def command(self, value, marker):
        start = len(self.transcript)
        self.process.stdin.write(value + '\n'); self.process.stdin.flush()
        try:
            self.wait(marker, 5)
        except (ValueError, TimeoutError):
            if re.search('AbstractMethodError|NoSuchMethodError', ''.join(self.transcript[start:])):
                raise ArtifactIncompatible('Command is incompatible with the pinned runtime API')
            raise
    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write('shutdown\n'); self.process.stdin.flush()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.terminate(); self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill(); self.process.wait(timeout=5)
            self.reserved.close()
            self.reader.join(timeout=2)
            self.process.stdin.close()
            self.process.stdout.close()
            (self.directory / 'console.log').write_text(''.join(self.transcript))


def run(suite, plugin, java, runtime, work, rounds=3, samples=10, adapter='connection-guard', assets=None, geo_db=None, sqlite=None, warmups=2, round_offset=0):
    require(sha(runtime) == RUNTIME_SHA, 'Runtime must match the pinned Velocity 3.4.0 build 566')
    version = subprocess.run([str(java), '-version'], capture_output=True, text=True, timeout=10, check=True).stderr
    require(re.search(r'version "21[.\"]', version), 'This native fixture requires Java 21')
    require(1 <= rounds <= 10 and 1 <= samples <= 100 and adapter in {'connection-guard', 'georestrict', 'sqidgeon-antivpn'}, 'Invalid controlled run')
    require(type(warmups) is int and 1 <= warmups <= 100, 'Invalid warmup count')
    require(type(round_offset) is int and 0 <= round_offset <= 9 and round_offset + rounds <= 10, 'Invalid round offset')
    require(rounds * (samples + warmups) * sum(c.get('concurrency', 1) for c in suite['cases']) <= 40000, 'Measurement row budget exceeded')
    work = Path(work).resolve(); require(not work.exists(), 'Preserve earlier runs'); work.mkdir(mode=0o700, parents=True)
    (work / 'suite.json').write_text(json.dumps(suite, indent=2) + '\n')
    plugin_info = inspect_jar(plugin)
    require(plugin_info['descriptor']['id'] == ('antivpn' if adapter == 'sqidgeon-antivpn' else adapter), 'The adapter must match the actual plugin descriptor')
    require(adapter != 'sqidgeon-antivpn' or sqlite is not None, 'Sqidgeon requires an explicit SQLite JDBC dependency')
    harness = work / 'harness'; harness.mkdir()
    source = Path(__file__).resolve().parents[1] / 'java/LoopbackSecurityManager.java'
    subprocess.run([str(Path(java).with_name('javac')), '--release', '21', '-d', str(harness), str(source)],
                   check=True, capture_output=True, timeout=30)
    asset_hashes = {path.relative_to(assets).as_posix(): sha(path) for path in sorted(Path(assets).rglob('*.jar'))} if assets else {}
    import yaml
    environment = dict(java=version, os=os.uname().sysname, os_release=os.uname().release, machine=os.uname().machine,
                       hardware=hardware(), python=platform.python_version(), runtime_sha256=RUNTIME_SHA,
                       pyyaml=yaml.__version__,
                       heap='64m/256m', event_loop_threads=2, transport='NIO', subject_transport='loopback_proxy_v2', network='loopback_tcp_dns_metadata_allowed', protocol=760)
    inputs = {p.relative_to(Path(__file__).resolve().parents[1]).as_posix(): sha(p)
              for p in sorted(Path(__file__).resolve().parent.glob('*.py'))}
    inputs['java/LoopbackSecurityManager.java'] = sha(source)
    result = dict(schema=1, kind='measured', product=adapter, version=plugin_info['descriptor']['version'],
                  suite_sha256=fingerprint(suite), adapter_sha256=fingerprint(inputs), input_sha256=inputs, artifact_sha256=sha(plugin),
                  environment_sha256=fingerprint(environment), environment=environment, asset_sha256=asset_hashes,
                  layer='native_velocity_login_gate', profile='controlled_enforce', cache_policy='declared_per_case',
                  sampling=dict(rounds=rounds, samples=samples, warmups=warmups), rows=[], logs=[], config_sha256=[], environment_errors=[], live_accuracy_tested=False,
                  backend_join_tested=False, real_account_authentication_tested=False)
    result['sampling_purpose'] = 'functional_qualification_with_exploratory_timing'
    result['case_conditions'] = {c['id']: dict(source=('local_mmdb' if c['track'] == 'geo' and adapter == 'connection-guard' else 'local_cidr' if adapter == 'sqidgeon-antivpn' else 'owned_http'),
        cache='warm' if c['fixture'] == 'warm_cache' else 'cold', concurrency=c.get('concurrency', 1)) for c in suite['cases']}
    result['host_load_average_at_start'] = list(os.getloadavg())
    result['resource_scope'] = 'Whole proxy RSS observed before/after batches, not peak RSS or plugin-only memory. Coarse ps CPU time.'
    if geo_db: result['geo_database_sha256'] = sha(geo_db)
    if adapter == 'sqidgeon-antivpn':
        result['profile'] = 'controlled_local_cidr_and_manual_rules'
        result['sqlite_sha256'] = sha(sqlite)
    for round_number in range(round_offset, round_offset + rounds):
        # Alternating order reduces deterministic time/order bias across paired runs.
        cases = suite['cases'] if round_number % 2 == 0 else list(reversed(suite['cases']))
        for case in cases:
            fixture_name = case['fixture']
            unsupported = fixture_name == 'geo_gb' or case['track'] == 'geo' and adapter == 'connection-guard' and not geo_db
            if adapter == 'georestrict':
                unsupported = unsupported or case['track'] == 'rules'
            if adapter == 'sqidgeon-antivpn':
                unsupported = fixture_name not in {'positive', 'negative', 'manual_deny', 'manual_allow', 'shared_positive', 'unique_positive', 'no_cache'}
            if unsupported:
                result['rows'].append(dict(case_id=case['id'], round=round_number, phase='measure', sample=0,
                                           status='unsupported', reason='adapter_not_qualified_for_this_case'))
                continue
            http = owned = None
            directory = work / (str(round_number) + '-' + case['id'])
            try:
                http = ProviderFixture(fixture_name)
                if adapter == 'connection-guard':
                    files = cg_config(plugin, fixture_name, 'http://127.0.0.1:' + str(http.server.server_port), geo_db)
                elif adapter == 'georestrict':
                    files = georestrict_config(fixture_name, 'http://127.0.0.1:' + str(http.server.server_port))
                else:
                    listed = fixture_name not in {'manual_deny', 'manual_allow'}
                    config = ('database.type=sqlite\nlog-connections=false\nreverse-dns-check=false\n'
                        'firehol.force-update-on-start=false\nfirehol.update-interval-hours=24\n'
                        'lists.firehol-level1=false\nlists.firehol-anonymous=false\nlists.x4bnet-datacenter=false\n'
                        'lists.x4bnet-vpn=' + str(listed).lower() + '\n')
                    content = b'192.0.2.77/32\n' if fixture_name == 'negative' else b'81.2.69.128/27\n2001:218::/32\n'
                    files = {'config.properties': config.encode(), 'firehol/x4bnet_vpn.netset': content}
                result['config_sha256'].append(dict(case_id=case['id'], round=round_number,
                    files={name: hashlib.sha256(body).hexdigest() for name, body in sorted(files.items())}))
                owned = Runtime(directory, java, runtime, plugin, files, assets, harness, sqlite=sqlite)
                owned.ready()
                if adapter == 'sqidgeon-antivpn':
                    if not any('Matcher ready:' in line for line in owned.transcript): owned.wait('Matcher ready:', 5)
                if case['track'] == 'geo' and adapter == 'connection-guard':
                    owned.command('cg local import geo GeoIP2-Country-Test.mmdb 2026-02-04T22:49:29Z', 'Validated local generation activated')
                if fixture_name in {'manual_deny', 'manual_allow', 'deny_allow_conflict', 'expiry'}:
                    effects = ['deny', 'allow'] if fixture_name == 'deny_allow_conflict' else ['allow' if fixture_name == 'manual_allow' else 'deny']
                    for effect in effects:
                        if adapter == 'connection-guard':
                            owned.command('cg ' + effect + ' add ' + case['ip'] + ' ALL ' + ('1s' if fixture_name == 'expiry' else 'permanent') + ' controlled-benchmark', 'Stored ')
                        else:
                            owned.command(('vpnallow' if effect == 'allow' else 'vpnblock') + ' add ' + case['ip'], 'added')
                    if fixture_name == 'expiry':
                        proof = login(owned.port, case['ip'], 'BExpiryProof')
                        require(proof['outcome'] == 'DENY' and http.calls == 0, 'Expiry rule did not refuse before its deadline')
                        time.sleep(1.1)
                for phase, count in [('warmup', warmups), ('measure', samples)]:
                    for sample in range(count):
                        if adapter == 'georestrict' and fixture_name != 'warm_cache':
                            owned.command('georestrict purgecache', 'cache purged successfully')
                        time.sleep(.02)  # Drain completed login callbacks outside the timed region.
                        before = http.calls
                        resource_before = resources(owned.process.pid)
                        parallel = case.get('concurrency', 1)
                        def perform(index):
                            import ipaddress
                            ip = str(ipaddress.ip_address(case['ip']) + index) if fixture_name == 'unique_positive' else case['ip']
                            return login(owned.port, ip, 'B' + str(round_number) + '_' + phase[0] + str(sample) + '_' + str(index))
                        start = time.perf_counter_ns()
                        with concurrent.futures.ThreadPoolExecutor(max_workers=parallel) as pool:
                            values = list(pool.map(perform, range(parallel)))
                        elapsed = time.perf_counter_ns() - start
                        requests = http.calls - before
                        resource_after = resources(owned.process.pid)
                        for index, observation in enumerate(values):
                            observation.update(case_id=case['id'], round=round_number, phase=phase, sample=sample * parallel + index,
                                status='measured', requests=requests if parallel == 1 else None,
                                batch_id=str(round_number) + '-' + case['id'] + '-' + phase + '-' + str(sample),
                                batch_requests=requests, batch_elapsed_ns=elapsed, resource_before=resource_before, resource_after=resource_after)
                            result['rows'].append(observation)
                # Native 429 backoff legitimately suppresses later retries. Prove the owned
                # source was exercised at least once across warmup/measurement, not per retry.
                if adapter != 'sqidgeon-antivpn' and case['track'] not in {'rules', 'geo'} and http.calls == 0:
                    result['environment_errors'].append(dict(case_id=case['id'], round=round_number, reason='owned_source_not_exercised'))
            except (OSError, ValueError, TimeoutError, subprocess.SubprocessError) as error:
                result['rows'].append(dict(case_id=case['id'], round=round_number, phase='setup', sample=0,
                                           status='error', reason=('artifact_runtime_compatibility' if isinstance(error, ArtifactIncompatible) else 'environment_or_adapter_' + type(error).__name__)))
                if isinstance(error, OSError): result['rows'][-1]['os_errno'] = error.errno
            finally:
                if owned:
                    owned.close()
                    audit = directory / 'permission-audit.log'
                    if audit.exists() and audit.stat().st_size:
                        result['environment_errors'].append(dict(case_id=case['id'], round=round_number, reason='fixture_permission_denial'))
                    require(sha(directory / 'velocity.jar') == RUNTIME_SHA and sha(directory / 'plugins/plugin-under-test.jar') == result['artifact_sha256'], 'A measured JAR was modified')
                    result['logs'].append(dict(case_id=case['id'], round=round_number, sha256=sha(directory / 'console.log'),
                                               process_exit=owned.process.returncode))
                if http:
                    http.close()
    return result
