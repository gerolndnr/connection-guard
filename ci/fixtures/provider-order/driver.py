#!/usr/bin/env python3
"""Actual-JAR Java 8 lookup profile cases; owned loopback HTTP only, no provider API."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
EXPECTED={'embedded_template_new_order_and_limits','zowi_clean_stops_without_other_recipients','timeout_unconfirmed_blackbox_falls_to_ipquery','sixty_minute_cap_preserved_and_ordinary_fallback_used','explicit_existing_selection_order_and_key_unchanged','proxycheck_opt_in_is_a_late_fallback'}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--work',type=Path,required=True);a=p.parse_args()
    artifact=a.artifact.resolve(strict=True);assert artifact.is_relative_to(ROOT/'build/libs')
    work=a.work.resolve();assert not work.exists();work.mkdir(parents=True,mode=0o700);classes=work/'classes';classes.mkdir()
    source=Path(__file__).with_name('ProviderOrderJarCases.java')
    subprocess.run([str(a.java.with_name('javac')),'-encoding','UTF-8','-cp',str(artifact),'-d',str(classes),str(source)],check=True,timeout=30)
    run=subprocess.run([str(a.java),'-Xms64m','-Xmx256m','-cp',str(artifact)+':'+str(classes),'com.github.gerolndnr.connectionguard.core.vpn.ProviderOrderJarCases'],text=True,capture_output=True,timeout=60)
    (work/'console.log').write_text(run.stdout+run.stderr);assert run.returncode==0,'Inspect owned console.log; no success receipt emitted.'
    cases=[line.partition(' ')[2] for line in run.stdout.splitlines() if line.startswith('PROVIDER_ORDER_JAR_CASE ')];assert set(cases)==EXPECTED and len(cases)==len(EXPECTED)
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    receipt=dict(schema=1,kind='actual-jar-provider-order-loopback',artifact_sha256=digest(artifact),driver_sha256=digest(Path(__file__)),fixture_sha256=digest(source),
                 cases=cases,java=str(a.java),owned_loopback_only=True,external_provider_requests=0,native_login_tested=False,competitive_accuracy_tested=False)
    (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
