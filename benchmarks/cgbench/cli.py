import argparse
import json
from pathlib import Path
from . import analysis, artifacts, core, native, matrix, export
from .model import fingerprint, read, require, validate_suite, write

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description='Connection Guard controlled comparative benchmarks')
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ['run-core', 'run-velocity']:
        p = commands.add_parser(name)
        p.add_argument('--suite', type=Path, default=ROOT / 'datasets' / ('controlled-native-v1.json' if name == 'run-velocity' else 'controlled-v1.json'))
        p.add_argument('--artifact', type=Path, required=True)
        p.add_argument('--java', type=Path, required=True)
        p.add_argument('--work', type=Path, required=True)
        p.add_argument('--rounds', type=int, default=3); p.add_argument('--samples', type=int, default=4)
        if name == 'run-velocity':
            p.add_argument('--warmups', type=int, default=2)
            p.add_argument('--runtime', type=Path, required=True)
            p.add_argument('--adapter', choices=['connection-guard', 'georestrict', 'sqidgeon-antivpn'], default='connection-guard')
            p.add_argument('--assets', type=Path)
            p.add_argument('--geo-db', type=Path)
            p.add_argument('--sqlite', type=Path)
    p = commands.add_parser('report'); p.add_argument('receipt', type=Path); p.add_argument('--suite', type=Path, default=ROOT / 'datasets/controlled-v1.json')
    p = commands.add_parser('compare'); p.add_argument('left', type=Path); p.add_argument('right', type=Path); p.add_argument('--suite', type=Path, required=True)
    p = commands.add_parser('run-matrix'); p.add_argument('--manifest', type=Path, required=True); p.add_argument('--work', type=Path, required=True)
    p = commands.add_parser('export'); p.add_argument('receipts', nargs='+', type=Path); p.add_argument('--suite', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p = commands.add_parser('accuracy'); p.add_argument('--observations', type=Path, required=True)
    p = commands.add_parser('fetch-modrinth'); p.add_argument('slug'); p.add_argument('--game-version', default='1.21.11'); p.add_argument('--destination', type=Path, required=True)
    p = commands.add_parser('plan'); p.add_argument('--suite', type=Path, default=ROOT / 'datasets/controlled-v1.json')
    args = parser.parse_args()
    if args.command == 'run-matrix':
        with matrix.exclusive_run():
            receipts, suite = matrix.run(read(args.manifest), args.work)
            export.save(args.work / 'export', receipts, suite)
        print(json.dumps({k: analysis.summarize(v, suite)['coverage'] for k, v in receipts.items()}, indent=2))
        return
    if args.command.startswith('run-'):
        suite = validate_suite(read(args.suite))
        if args.command == 'run-core':
            result = core.run(suite, args.artifact, args.java, args.work, args.rounds, args.samples)
        else:
            with matrix.exclusive_run():
                result = native.run(suite, args.artifact.resolve(), args.java.resolve(), args.runtime.resolve(), args.work,
                                    args.rounds, args.samples, args.adapter, args.assets.resolve() if args.assets else None,
                                    args.geo_db.resolve() if args.geo_db else None, args.sqlite.resolve() if args.sqlite else None, args.warmups)
        write(args.work / 'receipt.json', result)
        report = analysis.summarize(result, suite)
        write(args.work / 'summary.json', report)
        print(json.dumps({'receipt': str(args.work / 'receipt.json'), 'summary': report['coverage'], 'layer': result['layer']}, indent=2))
    elif args.command == 'report':
        suite = validate_suite(read(args.suite)); result = read(args.receipt)
        require(result['suite_sha256'] == fingerprint(suite), 'Dataset does not match the recorded measurement')
        print(json.dumps(analysis.summarize(result, suite), indent=2))
    elif args.command == 'compare':
        print(json.dumps(analysis.compare(read(args.left), read(args.right), validate_suite(read(args.suite))), indent=2))
    elif args.command == 'export':
        receipts = {p.stem: read(p) for p in args.receipts}; suite = validate_suite(read(args.suite))
        require(all(r['suite_sha256'] == fingerprint(suite) for r in receipts.values()), 'Export dataset mismatch')
        export.save(args.output, receipts, suite)
    elif args.command == 'accuracy':
        value = read(args.observations)
        require(value.get('schema') == 1 and value.get('kind') == 'verified_endpoints' and isinstance(value.get('observations'), list), 'Expected owner-verified endpoint observations')
        print(json.dumps(analysis.accuracy(value['observations']), indent=2))
    elif args.command == 'fetch-modrinth':
        print(json.dumps(artifacts.fetch_modrinth(args.slug, args.game_version, args.destination), indent=2))
    else:
        suite = validate_suite(read(args.suite))
        print(json.dumps({'suite_sha256': fingerprint(suite), 'cases': len(suite['cases']), 'tracks': sorted(set(c['track'] for c in suite['cases'])),
                          'live_accuracy': 'requires independently verified current endpoints; absent in controlled-v1'}, indent=2))


if __name__ == '__main__':
    main()
