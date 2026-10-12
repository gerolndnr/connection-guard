#!/usr/bin/env python3
"""Actual-JAR Java 8 lookup profile cases; owned loopback HTTP only, no provider API."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
EXPECTED={'configured_default_and_fresh_cache_preserve_one_request','preset_preview_is_read_only_and_spends_no_quota',
          'reload_clear_and_lowered_cap_do_not_replenish_reservations','vpn_and_geo_share_the_same_daily_cap',
          'local_only_suppresses_http_geo_and_old_api_cache_facts','local_evidence_survives_exhaustion_without_lowering_consensus',
          'one_hundred_dual_scope_callers_share_one_http_and_two_quota_layers'}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--java',type=Path,required=True);p.add_argument('--work',type=Path,required=True);a=p.parse_args()
    artifact=a.artifact.resolve(strict=True);assert artifact.is_relative_to(ROOT/'build/libs')
    work=a.work.resolve();assert not work.exists();work.mkdir(parents=True,mode=0o700);classes=work/'classes';classes.mkdir()
    source=Path(__file__).with_name('ApiProfileJarCases.java')
    subprocess.run([str(a.java.with_name('javac')),'-encoding','UTF-8','-cp',str(artifact),'-d',str(classes),str(source)],check=True,timeout=30)
    run=subprocess.run([str(a.java),'-Xms64m','-Xmx256m','-cp',str(artifact)+':'+str(classes),'com.github.gerolndnr.connectionguard.core.http.ApiProfileJarCases'],text=True,capture_output=True,timeout=45)
    (work/'console.log').write_text(run.stdout+run.stderr);assert run.returncode==0,'Inspect owned console.log; no success receipt emitted.'
    cases=[line.partition(' ')[2] for line in run.stdout.splitlines() if line.startswith('API_PROFILE_JAR_CASE ')];assert set(cases)==EXPECTED and len(cases)==len(EXPECTED)
    digest=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
    receipt=dict(schema=1,kind='actual-jar-api-profiles-loopback',artifact_sha256=digest(artifact),driver_sha256=digest(Path(__file__)),fixture_sha256=digest(source),
                 cases=cases,java=str(a.java),owned_loopback_only=True,external_provider_requests=0,native_login_tested=False,competitive_accuracy_tested=False)
    (work/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
