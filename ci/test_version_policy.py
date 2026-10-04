#!/usr/bin/env python3
"""Actual local Git histories reproduce the omitted 0.4.11 hotfix merge and version drift."""
from pathlib import Path
import subprocess
import tempfile
from verify_version import verify

def main():
    cases = []
    with tempfile.TemporaryDirectory(prefix='cg-version-history-') as temp:
        repo = Path(temp)/'repository'
        repo.mkdir()
        def git(*args):
            result = subprocess.run(['git','-c','core.hooksPath=/dev/null',*args],cwd=repo,capture_output=True,text=True,timeout=15)
            if result.returncode: raise RuntimeError('Local version history fixture failed: '+str(args))
            return result.stdout.strip()
        def commit(version, message):
            (repo/'build.gradle.kts').write_text('version = "'+version+'"\n')
            git('add','build.gradle.kts');git('commit','-qm',message)
        def rejected(path, phrase, label):
            try: verify(path)
            except ValueError as error:
                assert phrase in str(error),(phrase,str(error));cases.append(label)
            else: raise AssertionError('Unsafe version/history unexpectedly accepted: '+label)
        git('init','-q','-b','master');git('config','user.email','fixture@example.invalid');git('config','user.name','Version fixture')
        commit('0.4.10','Base release');git('tag','0.4.10')
        git('switch','-qc','hotfix');commit('0.4.11','Released hotfix');git('tag','0.4.11')
        git('switch','-q','master')
        rejected(repo,'missing from this branch history','unmerged latest hotfix is rejected')
        git('merge','--no-ff','-qm','Reconcile hotfix','hotfix')
        commit('0.5.0-SNAPSHOT','Next development version')
        assert verify(repo)['latest_stable_is_ancestor'];cases.append('merged hotfix with newer snapshot is accepted')
        commit('0.4.10','Accidental old build version')
        rejected(repo,'older than stable release','old build version after merge is rejected')
        commit('0.4.11','Accidental reuse of published version')
        rejected(repo,'immutable release','new commits cannot reuse released version')
        git('switch','-q','--detach','0.4.11')
        assert verify(repo)['version']=='0.4.11';cases.append('exact unchanged release commit is accepted')
        shallow=Path(temp)/'shallow'
        subprocess.run(['git','-c','core.hooksPath=/dev/null','clone','-q','--depth=1',repo.as_uri(),str(shallow)],check=True,capture_output=True,timeout=20)
        rejected(shallow,'complete history','shallow checkout cannot conceal release ancestry')
    assert len(cases)==6
    print('Version/history regression guards passed: '+str(len(cases)))
    for case in cases:print('PASS '+case)

if __name__=='__main__':main()
