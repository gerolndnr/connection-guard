#!/usr/bin/env python3
"""Portable actual offline Velocity language selection/reload. Uses only sibling public fixtures."""
import argparse, copy, hashlib, importlib.util, json, re, socket, subprocess, time, zipfile
from pathlib import Path
import yaml

SOURCE=Path(__file__).resolve().parent
WEBHOOK=SOURCE.parent/'webhooks'
spec=importlib.util.spec_from_file_location('owned_language_proxy',WEBHOOK/'driver.py')
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['artifact','sha256','proxy','java','work']:parser.add_argument('--'+name,required=True)
    args=parser.parse_args();artifact=Path(args.artifact).resolve();proxy=Path(args.proxy).resolve();java=Path(args.java).resolve();work=Path(args.work).resolve()
    assert re.fullmatch('[0-9a-f]{64}',args.sha256) and hashlib.sha256(artifact.read_bytes()).hexdigest()==args.sha256
    assert hashlib.sha256(proxy.read_bytes()).hexdigest()==base.PROXY_SHA
    assert not work.exists();work.mkdir(parents=True,mode=0o700)
    classes=work/'classes';classes.mkdir()
    subprocess.run([str(java.with_name('javac')),'--release','17','-proc:none','-cp',str(artifact)+':'+str(proxy),'-d',str(classes),str(WEBHOOK/'NativeWebhookFixture.java')],check=True,timeout=30)
    addon=work/'fixture.jar'
    with zipfile.ZipFile(addon,'w') as out:
        for file in sorted(classes.rglob('*.class')):out.write(file,file.relative_to(classes).as_posix())
        out.write(WEBHOOK/'velocity-plugin.json','velocity-plugin.json')
    reservation=socket.socket();reservation.bind(('127.0.0.1',0));instance=None
    result={'status':'running','artifact_sha256':args.sha256,'addon_sha256':hashlib.sha256(addon.read_bytes()).hexdigest(),
        'driver_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'proxy_driver_sha256':hashlib.sha256((WEBHOOK/'driver.py').read_bytes()).hexdigest(),
        'fixture_source_sha256':hashlib.sha256((WEBHOOK/'NativeWebhookFixture.java').read_bytes()).hexdigest(),'proxy':'Velocity 3.4.0 build 566',
        'proxy_sha256':base.PROXY_SHA,'java':21,'minecraft_protocol':760,'real_discord_tested':False,'live_upstream_tested':False,
        'account_authentication_tested':False,'backend_join_tested':False,'new_cost_eur':0,'cases':[]}
    def record(name):result['cases'].append(name);print(name,flush=True)
    try:
        with zipfile.ZipFile(artifact) as archive:settings=yaml.safe_load(archive.read('config.yml'))
        for value in settings['provider']['vpn'].values():value['enabled']=False
        settings['provider']['geo']['service']='Disabled';settings['provider']['cache']['type']='SQLite';settings['operation']['mode']='ENFORCE'
        settings['integrations']['providers']={'enabled':True,'sources':[{'id':'webhook-fixture','voting':True,'daily-budget':100,'minute-budget':100}]}
        settings['integrations']['observers']={'enabled':False,'ids':[]};settings['required-positive-flags']=1
        settings['behavior']['vpn']['exemptions']=[];settings['behavior']['geo']['exemptions']=[]
        settings['behavior']['vpn']['notify-staff']=False;settings['behavior']['vpn']['kick-player']=True
        settings['behavior']['geo']['kick-player']=False
        settings['behavior']['vpn']['send-webhook']['enabled']=False;settings['behavior']['geo']['send-webhook']['enabled']=False
        settings['message-language']='en'
        instance=base.Proxy(work/'proxy',artifact,proxy,java,settings,addon,reservation.getsockname()[1]);instance.ready()
        data=instance.directory/'plugins/connection-guard';translation=data/'translation'
        def idle():
            deadline=time.monotonic()+5
            while time.monotonic()<deadline:
                value=instance.command('fixture-webhook status','WH_FIXTURE calls=')
                if 'lookupIdle=true' in value:return
                time.sleep(.02)
            raise AssertionError('Owned lookup did not retire')
        def reload(marker):idle();instance.write(settings);instance.command('cg reload',marker)
        def deny(name,text):
            response=base.login(instance.port,name);assert response!='LOGIN_SUCCESS' and text in response,response
            idle()
        # Optional providers register after core startup; activate the explicit selected addon per its documented contract.
        reload('Config has been reloaded!')
        instance.command('cg help','Overview of commands');deny('LangEnglish','It looks like you are using')
        record('actual-english-startup-help-and-positive-denial')
        settings['message-language']='de';reload('Konfiguration neu geladen!')
        assert (translation/'de.yml').is_file() and 'Zugangsregeln' in (translation/'de.yml').read_text()
        instance.command('cg help','Befehlsübersicht');deny('LangGerman','Deine Verbindung')
        instance.command('cg explain invalid','Verwende eine IPv4-/IPv6-Adresse')
        instance.command('cg doctor','Prüfe die Weiterleitung der Client-IP')
        record('actual-german-copy-selection-help-operations-and-denial')
        settings['message-language']='es';reload('¡Configuración recargada!')
        instance.command('cg help','Comandos disponibles');deny('LangSpanish','Tu conexión')
        record('actual-spanish-selection-unicode-help-and-denial')
        custom={'messages':{'vpn-block':'Operador: conexión rechazada %NAME%'}}
        (translation/'es.yml').write_text(yaml.safe_dump(custom,allow_unicode=True))
        reload('¡Configuración recargada!');instance.command('cg help','Comandos disponibles');deny('LangOverride','Operador: conexión rechazada LangOverride')
        assert yaml.safe_load((translation/'es.yml').read_text())==custom
        record('existing-partial-operator-file-preserved-with-spanish-fallback')
        settings['message-language']='../private-token';reload('Recarga rechazada')
        deny('LangBadPath','Operador: conexión rechazada LangBadPath');assert not (data/'private-token.yml').exists()
        assert all('private-token' not in line for line in instance.transcript)
        record('unsafe-selection-rejected-keeps-active-language-and-denial')
        settings['message-language']='es';settings['operation']['mode']='OBSERVE';settings['behavior']['vpn']['kick-player']=False
        (translation/'es.yml').write_text('messages:\n  help: 17\n')
        reload('Recarga rechazada');deny('LangBadType','Operador: conexión rechazada LangBadType')
        record('invalid-template-type-rejects-whole-mode-and-language-draft')
        (translation/'es.yml').write_bytes(b'x'*65537);reload('Recarga rechazada');deny('LangBigFile','Operador: conexión rechazada LangBigFile')
        record('oversized-file-rejected-keeps-active-security-and-custom-template')
        settings['operation']['mode']='ENFORCE';settings['behavior']['vpn']['kick-player']=True;settings['message-language']='community'
        custom_file=translation/'community.yml';custom_file.write_text('messages:\n  help:\n    - Community help marker\n')
        reload('Config has been reloaded!');instance.command('cg help','Community help marker');deny('LangCommunity','It looks like you are using')
        record('unknown-custom-locale-preserved-with-explicit-english-fallback')
        outside=work/'outside.yml';outside.write_text('private-token');(translation/'linked.yml').symlink_to(outside)
        settings['message-language']='linked';reload('Reload rejected; active settings preserved.')
        deny('LangSymlink','It looks like you are using');assert outside.read_text()=='private-token'
        assert all('private-token' not in line for line in instance.transcript)
        record('symlink-selected-file-rejected-without-reading-or-overwriting-target')
        settings['message-language']='de';settings['required-positive-flags']=17
        reload('Reload rejected; active settings preserved.');deny('LangBadPolicy','It looks like you are using')
        record('valid-language-with-invalid-policy-does-not-activate-either-draft')
        settings['required-positive-flags']=1;reload('Konfiguration neu geladen!');deny('LangRestored','Deine Verbindung')
        record('valid-german-reselection-recovers-after-rejected-drafts')
        value=instance.command('fixture-webhook status','WH_FIXTURE calls=');assert 'queued=0' in value and 'active=0' in value
        result['guard_idle_at_end']=True
    except Exception:
        result['status']='failed';raise
    finally:
        if instance is not None:instance.close();result['proxy_stopped']=instance.process.poll() is not None;result['proxy_exit_code']=instance.process.returncode
        reservation.close();result['reserved_backend_socket_closed']=True
        if instance is not None:result['console_sha256']=hashlib.sha256((work/'proxy/console.log').read_bytes()).hexdigest()
        (work/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    assert result['proxy_stopped'] and result['proxy_exit_code']==0 and len(result['cases'])==11
    result['status']='passed';(work/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result),flush=True)
if __name__=='__main__':main()
