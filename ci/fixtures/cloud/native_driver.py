#!/usr/bin/env python3
"""Pinned native Cloud reload/lifecycle evidence on owned loopback, never production."""
import argparse, collections, gzip, hashlib, json, re, subprocess, sys, threading, time, zipfile
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import yaml


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def until(predicate, label, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = predicate()
        if result:
            return result
        time.sleep(.05)
    raise AssertionError('Bounded wait failed: ' + label)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operations-root', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--platform', choices=('paper', 'folia', 'bungee'), required=True)
    parser.add_argument('--fixture', required=True)
    args = parser.parse_args()
    root = args.operations_root.resolve()
    assert 'Ja, EULA akzeptieren und diese lokalen Tests durchführen' in (root / 'delivery/backend-runtime-scope.md').read_text()
    checkout = Path(__file__).resolve().parents[3]
    artifact = args.artifact.resolve()
    assert artifact.is_relative_to(checkout / 'build/libs') and digest(artifact) == args.sha256
    assert re.fullmatch('[a-z0-9-]{1,100}', args.fixture)
    sys.path.insert(0, str(root / 'tools'))
    from backend_fixture import Backend, RUNTIMES
    from release_backend_test import Clients
    from release_native_bungee_test import Bungee, RUNTIME, RUNTIME_SHA
    from release_velocity_test import JAVA, login
    family = 'native-bungee-smoke' if args.platform == 'bungee' else 'backend-smoke'
    work = root / '.runtime' / family / args.fixture
    assert not work.exists()
    work.mkdir(parents=True, mode=0o700)
    records = collections.deque(maxlen=256)
    state = {'desired': None, 'blocked': False, 'installs': 0, 'provider_calls': 0, 'commands': []}
    lock = threading.Lock()
    entered, release = threading.Event(), threading.Event()
    fixture_id = 'ins_' + 'A' * 24
    fixture_secret = 'cgs_' + 's' * 48

    class LocalServer(ThreadingHTTPServer):
        daemon_threads = True

    class Handler(BaseHTTPRequestHandler):
        def send_json(self, code, body):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass  # A native Cloud cancellation is expected during shutdown.

        def do_GET(self):
            if not self.path.startswith('/fixture-provider/'):
                return self.send_json(404, {})
            with lock:
                state['provider_calls'] += 1
            self.send_json(200, {'data': {'isVpn': True, 'vpnProvider': 'Owned synthetic fixture'}})

        def do_POST(self):
            assert self.path in ('/v1/installs', '/v1/sync')
            size = int(self.headers['Content-Length'])
            assert 0 < size <= 262144
            data = gzip.decompress(self.rfile.read(size))
            assert len(data) <= 262144
            body = json.loads(data)
            if self.path == '/v1/installs':
                with lock:
                    state['installs'] += 1
                return self.send_json(201, {'install_id': fixture_id, 'secret': fixture_secret,
                    'claimed': True, 'network_name': 'Owned fixture', 'link_code': None, 'link_url': None, 'next_sync_in': 5})
            assert self.headers.get('Authorization') == 'Bearer ' + fixture_id + '.' + fixture_secret
            with lock:
                records.append(body)
                desired = state['desired']
                blocked = state['blocked']
                commands = list(state['commands'])
            if blocked:
                entered.set()
                release.wait(15)
            self.send_json(200, {'claimed': True, 'network_name': 'Owned fixture', 'link_code': None,
                'link_url': None, 'accept_events': True, 'next_sync_in': 5, 'live': False,
                'commands': commands, 'config': desired})

        def log_message(self, *unused):
            pass

    http = LocalServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    result = {'status': 'running', 'artifact_sha256': args.sha256, 'platform': args.platform,
        'driver_sha256': digest(Path(__file__)), 'java': 21, 'cases': [],
        'real_account_authentication_tested': False, 'real_operator_pilot': False,
        'live_cloud_or_provider_tested': False, 'new_cost_eur': 0,
        'helper_sources': {name: digest(root / 'tools' / name) for name in ('backend_fixture.py',
            'release_backend_test.py', 'release_native_bungee_test.py', 'release_velocity_test.py', 'backend_client.cjs')}}
    instance = clients = None

    def record(name):
        result['cases'].append(name)
        (work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('PASS ' + args.platform + ' ' + name, flush=True)

    def request_snapshot():
        with lock:
            return list(records)

    def applied(version, ok):
        for body in reversed(request_snapshot()):
            report = body['status'].get('config_result')
            if report and report.get('version') == version and report.get('ok') is ok:
                return body
        return None

    def desired(version, values, reset=False):
        with lock:
            state['desired'] = {'version': version, 'reset': reset, 'values': values, 'keep_secrets': []}

    try:
        fixture_dir = checkout / 'ci/fixtures' / ('native-bungee' if args.platform == 'bungee' else 'platform-tasks')
        source = fixture_dir / ('MissingBungeeFixture.java' if args.platform == 'bungee' else 'PlatformContractFixture.java')
        descriptor = fixture_dir / ('plugin-missing.yml' if args.platform == 'bungee' else 'plugin.yml')
        libraries = [RUNTIME] if args.platform == 'bungee' else sorted((root / '.runtime/backend-fixture/compile-libraries').glob('*.jar'))
        if args.platform == 'bungee':
            assert digest(RUNTIME) == RUNTIME_SHA
            result.update(runtime_sha256=RUNTIME_SHA, runtime='BungeeCord build 2100')
        else:
            filename, expected = RUNTIMES[args.platform]
            assert digest(root / '.runtime/backend-fixture' / filename) == expected
            result.update(runtime_sha256=expected, runtime=filename)
        classes = work / 'classes'
        classes.mkdir()
        subprocess.run([str(Path(JAVA).with_name('javac')), '--release', '17' if args.platform == 'bungee' else '21',
            '-proc:none', '-cp', ':'.join(map(str, [artifact, *libraries])), '-d', str(classes), str(source)], check=True, timeout=30)
        addon = work / 'addon.jar'
        with zipfile.ZipFile(addon, 'w') as z:
            for p in sorted(classes.rglob('*.class')):
                z.write(p, p.relative_to(classes).as_posix())
            z.write(descriptor, 'bungee.yml' if args.platform == 'bungee' else 'plugin.yml')
        result.update(addon_sha256=digest(addon), fixture_source_sha256=digest(source))
        with zipfile.ZipFile(artifact) as z:
            settings = yaml.safe_load(z.read('config.yml'))
        for p in settings['provider']['vpn'].values():
            p['enabled'] = False
        settings['provider']['vpn']['custom'].update(enabled=True,
            **{'request-url': 'http://127.0.0.1:' + str(http.server_port) + '/fixture-provider/%IP%', 'request-header': []})
        settings['provider']['geo']['service'] = 'Disabled'
        settings['provider']['cache']['type'] = 'SQLite'
        settings['operation']['mode'] = 'OBSERVE'
        settings['required-positive-flags'] = 1
        settings['message-language'] = 'de'
        settings['cloud'] = {'enabled': True, 'endpoint': 'http://127.0.0.1:' + str(http.server_port), 'network-token': ''}
        settings['integrations']['providers']['enabled'] = False
        settings['integrations']['observers']['enabled'] = False
        for scope in ('vpn', 'geo'):
            settings['behavior'][scope]['exemptions'] = []
            settings['behavior'][scope]['notify-staff'] = False
            settings['behavior'][scope]['execute-command']['enabled'] = False
            settings['behavior'][scope]['send-webhook']['enabled'] = False
        settings['behavior']['vpn']['kick-player'] = True
        settings['behavior']['geo']['kick-player'] = False
        if args.platform == 'bungee':
            instance = Bungee(work / 'server', artifact, addon, settings, [], {}, False)
        else:
            instance = Backend(work / 'server', args.platform, artifact, settings, addon)
        instance.ready()
        data_dir = work / 'server/plugins/ConnectionGuard'
        language = data_dir / 'translation/de.yml'
        language.write_text('messages:\n  vpn-block: Betreiberblock %NAME%\n')
        instance.command('cg reload', 'Konfiguration neu geladen!')
        config = data_dir / 'config.yml'
        local_hash, language_hash = digest(config), digest(language)
        until(lambda: request_snapshot(), 'real native Cloud sync')
        assert request_snapshot()[-1]['status']['mode'] == 'OBSERVE'
        record('real-native-cloud-claim-and-sync-in-observe')
        if args.platform != 'bungee':
            clients = Clients(instance)

        def admit(name):
            if clients:
                clients.write('connect ' + name)
                clients.wait('CLIENT ' + name + ' login')
                instance.wait('FIXTURE joined ' + name)
                clients.write('close ' + name)
                clients.wait('CLIENT ' + name + ' end')
            else:
                assert 'LOGIN_SUCCESS' in login(instance.port, name)

        def deny(name):
            if clients:
                clients.write('connect ' + name)
                text = clients.denial(name)
            else:
                text = login(instance.port, name)
            assert 'Betreiberblock ' + name in text, text

        admit('CGCloudObserve')
        record('positive-provider-observe-allows-real-offline-login')
        desired(1, {'operation.mode': 'ENFORCE', 'required-positive-flags': 1})
        report = until(lambda: applied(1, True), 'valid full native remote draft')
        assert report['status']['mode'] == 'ENFORCE'
        overlay = data_dir / 'cloud/managed-config.json'
        previous_overlay = overlay.read_bytes()
        assert digest(config) == local_hash and digest(language) == language_hash
        deny('CGCloudEnforce')
        record('remote-enforce-applies-and-real-denial-preserves-custom-language')
        desired(2, {'operation.mode': 'OBSERVE', 'required-positive-flags': 16})
        report = until(lambda: applied(2, False), 'invalid full native remote draft')
        assert report['status']['mode'] == 'ENFORCE'
        assert 'values redacted' in report['status']['config_result']['message']
        assert overlay.read_bytes() == previous_overlay
        deny('CGCloudRejected')
        assert digest(config) == local_hash and digest(language) == language_hash
        record('invalid-whole-draft-keeps-effective-enforce-overlay-and-language')
        assert 'rule_expiry' in report['status']['capabilities']
        deadline = int(time.time() * 1000) + 20000
        temporary = {'id': 'cmd_' + 'T' * 20, 'type': 'access_rule.add', 'effect': 'ALLOW',
            'scope': 'VPN', 'target': '127.0.0.1', 'note': 'Owned synthetic expiry', 'expires_at': deadline}
        with lock:
            state['commands'] = [temporary]
        def acknowledged(command_id):
            for body in reversed(request_snapshot()):
                for value in body['command_results']:
                    if value['id'] == command_id:
                        return value
            return None
        ack = until(lambda: acknowledged(temporary['id']), 'native temporary rule acknowledgement')
        assert ack['ok'] is True
        rules_file = data_dir / 'access-rules.json'
        stored = json.loads(rules_file.read_text())
        assert any(rule['expiresAt'] == deadline for rule in stored), stored
        admit('CGCloudGranted')
        record('absolute-cloud-rule-persists-and-allows-before-deadline')
        # Cloud is deliberately unavailable through the actual deadline. Local policy must still end the grant.
        with lock:
            state['commands'] = []
            state['blocked'] = True
        until(entered.is_set, 'blocked sync during independent local expiry')
        until(lambda: int(time.time() * 1000) > deadline + 100, 'absolute expiry boundary', 25)
        deny('CGCloudExpired')
        record('expired-cloud-grant-denies-without-cloud-contact')
        with lock:
            state['blocked'] = False
        release.set()
        until(lambda: not json.loads(rules_file.read_text()), 'background expired-rule cleanup', 25)
        release.clear(); entered.clear()
        expired = dict(temporary, id='cmd_' + 'E' * 20, expires_at=1)
        with lock:
            state['commands'] = [expired]
        ack = until(lambda: acknowledged(expired['id']), 'already-expired acknowledgement')
        assert ack['ok'] is True and ack['message'] == 'Already expired'
        assert json.loads(rules_file.read_text()) == []
        with lock:
            state['commands'] = []
        record('delayed-expired-command-is-acknowledged-without-permanent-rule')
        desired(3, {}, True)
        report = until(lambda: applied(3, True), 'remote reset')
        assert report['status']['mode'] == 'OBSERVE'
        admit('CGCloudReset')
        assert digest(config) == local_hash and digest(language) == language_hash
        record('remote-reset-restores-local-config-and-real-observe-login')
        instance.command('cg cloud disable', 'bleibt nach Neustarts aus')
        assert (data_dir / 'cloud/disabled').is_file()
        instance.command('cg reload', 'Konfiguration neu geladen!')
        count = len(request_snapshot())
        time.sleep(6)
        assert len(request_snapshot()) == count
        admit('CGCloudDisabled')
        record('persisted-command-off-survives-reload-and-stops-sync')
        instance.command('cg cloud enable', 'Erster Sync innerhalb einer Minute')
        until(lambda: len(request_snapshot()) > count, 'reenabled native sync')
        assert not (data_dir / 'cloud/disabled').exists()
        with lock:
            assert state['installs'] == 1
            state['blocked'] = True
        until(entered.is_set, 'owned deliberately hanging Cloud HTTP request', 15)
        settings['operation']['mode'] = 'ENFORCE'
        instance.write(settings)
        instance.command('cg reload', 'Konfiguration neu geladen!')
        started = time.monotonic()
        deny('CGCloudHanging')
        duration = round((time.monotonic() - started) * 1000)
        assert duration < 3000, 'Login waited during independent 15s Cloud request'
        result['one_hanging_cloud_login_ms'] = duration
        record('hanging-cloud-request-does-not-delay-real-native-reload-or-denial')
        sequences = [b['seq'] for b in request_snapshot()]
        assert all(a < b for a, b in zip(sequences, sequences[1:]))
        assert digest(language) == language_hash
        result['sync_count'] = len(sequences)
        result['sync_sequences_strictly_increase'] = True
        if clients:
            clients.close()
        started = time.monotonic()
        instance.close()
        result['shutdown_duration_ms'] = round((time.monotonic() - started) * 1000)
        assert instance.process.returncode == 0
        assert result['shutdown_duration_ms'] < 8000
        assert not re.search(r'Error occurred while enabling ConnectionGuard|UnsupportedOperationException|Thread failed main thread check|FIXTURE context failure|Platform task unavailable', ''.join(instance.transcript))
        result['native_exit_code'] = instance.process.returncode
        record('clean-native-shutdown-cancels-live-cloud-request')
        result['status'] = 'passed'
    except Exception as failure:
        result['status'] = 'failed'
        result['error'] = str(failure)[:1000]
        raise
    finally:
        release.set()
        if clients:
            clients.close()
            (work / 'client.log').write_text(''.join(clients.transcript))
        if instance:
            instance.close()
        http.shutdown()
        http.server_close()
        (work / 'result.json').write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
