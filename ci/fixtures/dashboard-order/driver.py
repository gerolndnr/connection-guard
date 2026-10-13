#!/usr/bin/env python3
"""Actual-JAR Java 8 lookup profile cases; owned loopback HTTP only, no provider API."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
EXPECTED={'snapshot_unlocks_existing_gated_controls_without_enabling_recipients','partial_order_retains_unlisted_sources_and_ipapi_last','unknown_source_is_refused_without_a_silent_drop','empty_configured_order_is_valid_and_reported_exactly','optional_ipqualityscore_key_snapshot_uses_existing_redaction','managed_reload_rebuilds_cache_namespace_and_preserves_local_config'}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--work',type=Path,required=True);a=p.parse_args()
    artifact=a.artifact.resolve(strict=True);assert artifact.is_relative_to(ROOT/'build/libs')
    work=a.work.resolve();assert not work.exists();work.mkdir(parents=True,mode=0o700);classes=work/'classes';classes.mkdir()
    source=Path(__file__).with_name('DashboardOrderJarCases.java')
    subprocess.run([str(a.java.with_name('javac')),'-encoding','UTF-8','-cp',str(artifact),'-d',str(classes),str(source)],check=True,timeout=30)
    run=subprocess.run([str(a.java),'-Xms64m','-Xmx256m','-cp',str(artifact)+':'+str(classes),'com.github.gerolndnr.connectionguard.core.cloud.DashboardOrderJarCases',str(work/'data')],text=True,capture_output=True,timeout=45)
    (work/'console.log').write_text(run.stdout+run.stderr);assert run.returncode==0,'Inspect owned console.log; no success receipt emitted.'
    cases=[line.partition(' ')[2] for line in run.stdout.splitlines() if line.startswith('DASHBOARD_ORDER_JAR_CASE ')];assert set(cases)==EXPECTED and len(cases)==len(EXPECTED)
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    receipt=dict(schema=1,kind='actual-jar-dashboard-order-loopback',artifact_sha256=digest(artifact),driver_sha256=digest(Path(__file__)),fixture_sha256=digest(source),
                 cases=cases,java=str(a.java),owned_loopback_only=True,external_provider_requests=0,native_login_tested=False,competitive_accuracy_tested=False)
    (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
