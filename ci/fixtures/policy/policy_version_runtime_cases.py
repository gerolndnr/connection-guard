"""Native cases for an owned policy-versioning fixture; never a production operator.

Uses the existing runtime's real command, event and login bindings. All disk
faults are limited to its fresh private data directory. This module starts no
server itself. Its caller must wait until benchmark measurements have ended.
"""
import concurrent.futures
import copy
import json
import queue
import re
import shutil
import socket
import stat
import subprocess
import threading
import time


def restart_process(runtime, work):
    """Restart the same owned directory, preserving policy bytes and JVM limits."""
    arguments = list(runtime.process.args)
    runtime.close()
    assert runtime.process.returncode == 0
    saved = work / 'before-restart.log'
    assert not saved.exists()
    shutil.copyfile(runtime.directory / 'smoke.log', saved)
    if hasattr(runtime, 'backend_reservation'):
        # Bungee's close() releases this unused loopback backend. Keep it owned
        # again across the new process, so no real backend can receive clients.
        import yaml
        config_file = runtime.directory / 'config.yml'
        config = yaml.safe_load(config_file.read_text())
        target = config['servers']['owned-unused']['address']
        assert re.fullmatch(r'127\.0\.0\.1:[0-9]+', target)
        reserved = socket.socket()
        try:
            reserved.bind(('127.0.0.1', int(target.rsplit(':', 1)[1])))
        except OSError:
            reserved.bind(('127.0.0.1', 0))
            config['servers']['owned-unused']['address'] = '127.0.0.1:' + str(reserved.getsockname()[1])
            config_file.write_text(yaml.safe_dump(config, sort_keys=False))
        runtime.backend_reservation = reserved
    runtime.lines, runtime.transcript = queue.Queue(), []
    runtime.process = subprocess.Popen(arguments, cwd=runtime.directory, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, bufsize=1)
    process = runtime.process

    def consume():
        for line in process.stdout:
            line = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', line)
            runtime.transcript.append(line)
            runtime.lines.put(line)
        runtime.lines.put(None)
    threading.Thread(target=consume, daemon=True).start()
    runtime.ready()


def run_versions(runtime, connect, state, configure, restart, settings, data, result, pending_login):
    """Append only cases that actually complete; assertions leave no success receipt."""
    journal_file = data / 'access-rules.json'
    candidate_file = data / 'policy/version-candidate.json'
    rejected = 'Policy transaction rejected;'

    def status(send=True):
        header = (runtime.command('cg policy status', 'Policy owner=') if send
                  else runtime.wait('Policy owner='))
        hashes = runtime.wait('expected=')
        match = re.search(r'owner=(CONFIG|LOCAL_VERSION) revision=(\S+) mode=(OBSERVE|ENFORCE) rules=(\d+) activeDecisions=(\d+)', header)
        tokens = re.search(r'expected=([a-f0-9]{64}) policy=([a-f0-9]{64})', hashes)
        assert match and tokens, 'Incomplete native status'
        return dict(owner=match[1], revision=match[2], mode=match[3], rules=int(match[4]),
                    active=int(match[5]), expected=tokens[1], policy=tokens[2])

    def inspect():
        line = runtime.command('cg policy inspect version-candidate', 'candidate=')
        match = re.search(r'candidate=([a-f0-9]{64})', line)
        assert match
        return match[1]

    def commit(command):
        line = runtime.command(command, 'Policy committed:')
        committed = re.search(r'Policy committed: (p[0-9]+-[a-f0-9]{12})', line)
        assert committed
        current = status(False)
        assert current['revision'] == committed[1] and current['active'] == 0
        return current

    def activate():
        return commit('cg policy activate version-candidate ' + inspect() + ' ' + status()['expected'])

    def preserve(command, previous=None, bytes_before=None):
        previous = previous or status()
        before = bytes_before if bytes_before is not None else journal_file.read_bytes()
        runtime.command(command, rejected)
        assert status() == previous and journal_file.read_bytes() == before

    def case(name):
        result['cases'].append(name)

    candidate = dict(schema=1, mode='ENFORCE', vpn_failure='CLOSED', geo_failure='OPEN',
                     kick_vpn=False, kick_geo=True, geo_type='BLACKLIST', countries=[], rules=[])
    candidate_file.write_text(json.dumps(candidate))
    legacy = journal_file.read_bytes()
    assert isinstance(json.loads(legacy), list), 'Fresh suite must start with legacy rule storage'
    before = state(); baseline = status(); reviewed = inspect()
    assert baseline['owner'] == 'CONFIG' and baseline['active'] == 0
    assert state() == before and journal_file.read_bytes() == legacy
    case('native_inspect_status_do_not_write_rules_query_sources_or_activate')

    # First obtain an actual settings+rules base edit, then reject its old token.
    stored = runtime.command('cg deny add 198.51.100.1 all permanent Synthetic legacy version rule', 'Stored rule-')
    assert '198.51.100.1' in stored
    changed = status(); legacy = journal_file.read_bytes()
    preserve('cg policy activate version-candidate ' + reviewed + ' ' + baseline['expected'], changed, legacy)
    assert json.loads(legacy)[0]['target'] == '198.51.100.1'
    case('native_staff_rule_edit_invalidates_reviewed_activation_token')

    candidate_file.write_text(json.dumps(dict(candidate, kick_geo=False)))
    preserve('cg policy activate version-candidate ' + reviewed + ' ' + changed['expected'])
    candidate_file.write_text(json.dumps(candidate))
    case('native_candidate_file_edit_rejects_old_reviewed_hash')

    permissions = stat.S_IMODE(data.stat().st_mode)
    try:
        data.chmod(0o500)
        preserve('cg policy activate version-candidate ' + reviewed + ' ' + changed['expected'])
    finally:
        data.chmod(permissions)
    assert not (data / 'access-rules.before-policy.json').exists()
    connect('CGVersionIO', 'VPN', 'VPN_FLAG')
    case('native_filesystem_write_failure_keeps_original_deny_and_legacy_bytes')

    runtime.command('cg policy shadow start version-candidate 5m', 'Shadow ACTIVE: compared 0;')
    activated = activate()
    journal = json.loads(journal_file.read_text())
    original = journal['revisions'][1]['id']
    assert activated['owner'] == 'LOCAL_VERSION' and activated['policy'] == reviewed
    assert (data / 'access-rules.before-policy.json').read_bytes() == legacy
    assert stat.S_IMODE(journal_file.stat().st_mode) == 0o600
    runtime.command('cg policy shadow status', 'Shadow BASE_CHANGED: compared 0;')
    connect('CGVersionOn', 'ALLOW', 'FLAG_ALLOWED')
    case('native_durable_activation_changes_real_login_and_stops_shadow_with_private_legacy_backup')

    # Real pre-admission hook: the provider has not run, and no detector HTTP is pending.
    # Older per-lookup worker guards alone cannot prove this whole-login condition.
    runtime.command('fixture-policy hold-admission', 'POLICY_STATUS')
    before = state()
    begin = len(runtime.transcript)
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    try:
        future = pool.submit(pending_login, 'CGVersionHeld')
        deadline = time.monotonic() + 5
        while not any('POLICY_ADMISSION_WAITING' in line for line in runtime.transcript[begin:]):
            assert runtime.process.poll() is None and time.monotonic() < deadline
            time.sleep(.02)
        held = status()
        assert held['active'] == 1 and state()['calls'] == before['calls'] and state()['geoCalls'] == before['geoCalls']
        for command in [
            'cg policy activate version-candidate ' + reviewed + ' ' + held['expected'],
            'cg policy rollback ' + original + ' ' + held['expected'],
            'cg policy release ' + held['expected'],
        ]:
            preserve(command, held)
        runtime.command('cg reload', 'Reload rejected; active settings preserved.')
        assert status() == held
        late = runtime.command('cg deny add 127.0.0.1 all 5m Synthetic held admission deny', 'Stored rule-')
        late_id = re.search(r'Stored (rule-[a-f0-9-]+)', late)[1]
        runtime.command('fixture-policy release-admission', 'POLICY_STATUS')
        outcome = future.result(timeout=10)
        assert 'access policy' in outcome, outcome
    finally:
        # Idempotent unblocking, including assertion or command failures.
        try:
            if runtime.process.poll() is None:
                runtime.command('fixture-policy release-admission', 'POLICY_STATUS')
        finally:
            pool.shutdown(wait=True, cancel_futures=True)
    records = [line for line in runtime.transcript[begin:] if 'POLICY_OBS ' in line]
    deadline = time.monotonic() + 5
    while not records and time.monotonic() < deadline:
        time.sleep(.02); records = [line for line in runtime.transcript[begin:] if 'POLICY_OBS ' in line]
    assert len(records) == 1 and 'reason=ACCESS_RULE' in records[0] and 'outcome=DENY' in records[0]
    assert status()['active'] == 0 and state()['calls'] == before['calls'] + 1
    runtime.command('cg deny remove ' + late_id, 'Rule removed.')
    case('native_pre_provider_login_blocks_all_transitions_reload_but_accepts_late_staff_deny_with_one_later_detection')

    # The shared RULES edits may evict original (history is intentionally three).
    # Review/activate afresh to provide a current baseline for restart and rollback.
    released = commit('cg policy release ' + status()['expected'])
    assert released['owner'] == 'CONFIG'
    activated = activate(); journal = json.loads(journal_file.read_text())
    original = journal['revisions'][1]['id']
    committed_bytes = journal_file.read_bytes()
    staging = data / '.cg-policy-1234.tmp'; staging.write_text('Interrupted uncommitted bytes'); staging.chmod(0o600)
    restart()
    recovered = status()
    assert recovered['revision'] == activated['revision'] and recovered['policy'] == activated['policy']
    assert recovered['owner'] == 'LOCAL_VERSION' and journal_file.read_bytes() == committed_bytes and not staging.exists()
    connect('CGVersionBoot', 'ALLOW', 'FLAG_ALLOWED')
    case('native_restart_reads_exact_committed_policy_discards_staging_and_preserves_actual_login')

    saved_settings = copy.deepcopy(settings)
    try:
        settings['operation']['mode'] = 'OBSERVE'
        runtime.write(settings); runtime.command('cg reload', 'Reload rejected; active settings preserved.')
        assert status()['revision'] == recovered['revision'] and journal_file.read_bytes() == committed_bytes
        connect('CGVersionConf', 'ALLOW', 'FLAG_ALLOWED')
    finally:
        settings.clear(); settings.update(saved_settings); configure()
    case('native_local_owner_rejects_conflicting_config_without_publishing_policy')

    overlay = data / 'cloud/managed-config.json'; overlay.parent.mkdir(exist_ok=True)
    assert not overlay.exists()
    try:
        overlay.write_text(json.dumps({'version': 1, 'values': {'operation.mode': 'ENFORCE'}})); overlay.chmod(0o600)
        runtime.command('cg reload', 'Reload rejected; active settings preserved.')
        assert status()['owner'] == 'LOCAL_VERSION' and journal_file.read_bytes() == committed_bytes
        connect('CGVersionCloud', 'ALLOW', 'FLAG_ALLOWED')
    finally:
        overlay.unlink(missing_ok=True); configure()
    case('native_existing_dashboard_overlay_rejects_dual_decision_ownership_even_for_equal_native_value')

    try:
        journal_file.write_text('{"schema":1,"broken":true}')
        runtime.command('cg reload', 'Reload rejected; active settings preserved.')
        active = status(); assert active['revision'] == recovered['revision'] and active['policy'] == recovered['policy']
        connect('CGVersionBad', 'ALLOW', 'FLAG_ALLOWED')
    finally:
        journal_file.write_bytes(committed_bytes); journal_file.chmod(0o600); configure()
    case('native_corrupt_policy_reload_preserves_the_committed_live_behavior')

    rolled = commit('cg policy rollback ' + original + ' ' + status()['expected'])
    assert rolled['owner'] == 'LOCAL_VERSION'
    connect('CGVersionBack', 'VPN', 'VPN_FLAG')
    released = commit('cg policy release ' + rolled['expected'])
    assert released['owner'] == 'CONFIG'
    connect('CGVersionFree', 'VPN', 'VPN_FLAG')
    case('native_rollback_and_explicit_release_restore_real_configured_deny')

    expired = int(time.time() * 1000) - 1000
    expired_policy = dict(candidate, kick_vpn=True, rules=[dict(id='expired-native', effect='ALLOW', scope='ALL',
                         target='127.0.0.1', expires_at=expired, reason='Synthetic already expired grant')])
    candidate_file.write_text(json.dumps(expired_policy)); expired_revision = activate()['revision']
    connect('CGVersionOld', 'VPN', 'VPN_FLAG')
    candidate_file.write_text(json.dumps(candidate)); activate()
    connect('CGVersionAlt', 'ALLOW', 'FLAG_ALLOWED')
    commit('cg policy rollback ' + expired_revision + ' ' + status()['expected'])
    current = json.loads(journal_file.read_text())['revisions'][0]['policy']
    assert current['rules'][0]['expires_at'] == expired
    connect('CGVersionExpiry', 'VPN', 'VPN_FLAG')
    case('native_rollback_does_not_extend_or_revive_expired_grant')
    commit('cg policy release ' + status()['expected'])
    journal = json.loads(journal_file.read_text()); assert len(journal['revisions']) == 3
    runtime.command('cg policy history', 'Policy owner='); runtime.wait('expected=')
    for revision in journal['revisions']:
        line = runtime.wait('revision=' + revision['id'] + ' operation=')
        assert '127.0.0.1' not in line and 'Synthetic' not in line and 'expired-native' not in line
    case('native_history_is_bounded_and_does_not_print_private_targets_notes_or_observations')

    result['version_final_revision'] = status()['revision']
    result['version_history_length'] = len(journal['revisions'])
    result['version_private_data_mode'] = oct(stat.S_IMODE(journal_file.stat().st_mode))
