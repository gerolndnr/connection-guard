"""Portable evidence table; unsupported adapters are never scored as failures."""
import csv
import io
from . import analysis
from .model import require


def render(receipts, suite, title='Controlled benchmark results'):
    summaries = {name: analysis.summarize(value, suite) for name, value in receipts.items()}
    lines = [f'# {title}', '', 'Controlled fixtures, not a production VPN accuracy study. ALLOW means the proxy login gate passed; no backend join or real account authentication is tested.', '',
             '| Product | Version | Passed | Failed | Unsupported | Setup error | Not tested |',
             '| --- | --- | ---: | ---: | ---: | ---: | ---: |']
    for name, value in receipts.items():
        coverage = summaries[name]['coverage']
        lines.append('| ' + ' | '.join([name, value['version'], *[str(coverage.get(k, 0)) for k in ['pass', 'fail', 'unsupported', 'error', 'not_tested']]]) + ' |')
    lines += ['', 'Percentiles below are exploratory local timings. Small samples do not establish a reliable p99 or a product ranking. Resource figures cover the whole proxy and are observations, not peak memory or plugin allocations.', '',
              '| Case | Product | Verdict | Measured / expected | p50 ms | p95 ms | p99 ms | Max batch requests | Observed RSS MiB |',
              '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    output = io.StringIO(); writer = csv.writer(output)
    writer.writerow(['product', 'version', 'artifact_sha256', 'layer', 'profile', 'case', 'track', 'verdict', 'samples', 'expected_samples', 'p50_ms', 'p95_ms', 'p99_ms', 'max_batch_requests', 'observed_proxy_rss_bytes', 'qualification_errors', 'contract_failures'])
    for case in suite['cases']:
        for name, value in receipts.items():
            c = next(c for c in summaries[name]['cases'] if c['case_id'] == case['id'])
            maximum = max(c['batch_requests']) if c['batch_requests'] else None
            numbers = [c[k] for k in ['p50_ms', 'p95_ms', 'p99_ms']]
            rss = c['observed_proxy_rss_max_bytes']
            lines.append('| ' + ' | '.join([c['case_id'], name, c['verdict'], f"{c['samples']} / {c['expected_samples']}",
                *[f'{v:.2f}' if v is not None else '—' for v in numbers], str(maximum) if maximum is not None else '—', f'{rss / 1048576:.1f}' if rss else '—']) + ' |')
            writer.writerow([name, value['version'], value['artifact_sha256'], value['layer'], value['profile'], c['case_id'], c['track'], c['verdict'], c['samples'], c['expected_samples'], *numbers, maximum, rss, ';'.join(c['qualification_errors']), ';'.join(c['contract_failures'])])
    lines += ['', '## Qualification problems', '']
    for name, summary in summaries.items():
        for c in summary['cases']:
            if c['qualification_errors']:
                lines.append(f"- {name}, {c['case_id']}: " + ', '.join(c['qualification_errors']))
    lines += ['', '## Failed product contracts', '']
    for name, summary in summaries.items():
        for c in summary['cases']:
            if c['contract_failures']:
                lines.append(f"- {name}, {c['case_id']}: " + ', '.join(c['contract_failures']))
    lines += ['', '## Evidence', '']
    for name, value in receipts.items():
        lines += [f"- {name}: artifact `{value['artifact_sha256']}`, dataset `{value['suite_sha256']}`, adapter `{value['adapter_sha256']}`, environment `{value['environment_sha256']}`."]
    return '\n'.join(lines) + '\n', output.getvalue()


def save(directory, receipts, suite, title='Controlled benchmark results'):
    directory.mkdir(parents=True, exist_ok=True)
    require(not (directory / 'report.md').exists() and not (directory / 'results.csv').exists(), 'Preserve exported reports')
    markdown, csv_text = render(receipts, suite, title)
    (directory / 'report.md').write_text(markdown)
    (directory / 'results.csv').write_text(csv_text)
