#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
THREADS="${THREADS:-20}"
LOOPS="${LOOPS:-50}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_DIR:-/tmp/perf-matrix-${TIMESTAMP}}"
RUNS_CSV="${OUT_DIR}/runs.csv"
SUMMARY_CSV="${OUT_DIR}/summary.csv"
SUMMARY_MD="${OUT_DIR}/summary.md"

mkdir -p "${OUT_DIR}"
cat > "${RUNS_CSV}" <<'EOF'
label,dir,compose,run,throughput,avg_latency,min_latency,max_latency,error_count,sample_count,report_dir
EOF

configs=(
  "Robin + Dovecot LMTP (queued)|.perf/robin-dovecot|docker-compose.robin.yaml"
  "Robin + Dovecot LMTP (inline)|.perf/robin-dovecot|docker-compose.robin-inline.yaml"
  "Robin + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.robin.yaml"
  "Postfix + Dovecot LMTP|.perf/robin-dovecot|docker-compose.postfix.yaml"
  "Postfix + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.postfix.yaml"
  "Haraka + Dovecot LMTP|.perf/robin-dovecot|docker-compose.haraka.yaml"
  "Haraka + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.haraka.yaml"
  "Robin + Stalwart|.perf/robin-stalwart|docker-compose.robin.yaml"
  "Postfix + Stalwart|.perf/robin-stalwart|docker-compose.postfix.yaml"
  "Stalwart Bare|.perf/stalwart-bare|docker-compose.yaml"
)

run_index=0
for config in "${configs[@]}"; do
  IFS='|' read -r label rel_dir compose <<<"${config}"
  dir="${ROOT}/${rel_dir}"
  run_index=$((run_index + 1))
  printf '\n[%02d/10] %s\n' "${run_index}" "${label}"

  (
    cd "${dir}"
    docker compose -f "${compose}" down -v >/dev/null 2>&1 || true

    for run in 1 2 3 4 5; do
      if [[ "${run}" -eq 1 ]] && grep -q '^[[:space:]]*build:' "${compose}"; then
        docker compose -f "${compose}" build >/dev/null
        docker compose -f "${compose}" up -d >/dev/null
        sleep 35
      fi

      if [[ "${run}" -eq 5 ]]; then
        answer="y"
      else
        answer="n"
      fi

      printf '  run %d/5\n' "${run}"
      printf '%s\n' "${answer}" | COMPOSE_FILE="${compose}" THREADS="${THREADS}" LOOPS="${LOOPS}" "${SCRIPT_DIR}/run-test.sh"

      report_dir="$(find results -maxdepth 1 -type d -name '*-report' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
      python3 - "${label}" "${rel_dir}" "${compose}" "${run}" "${report_dir}" "${RUNS_CSV}" <<'PY'
import csv
import json
import sys

label, rel_dir, compose, run, report_dir, runs_csv = sys.argv[1:]
with open(f"{report_dir}/statistics.json", "r", encoding="utf-8") as fh:
    stats = json.load(fh)["Total"]

row = [
    label,
    rel_dir,
    compose,
    int(run),
    stats["throughput"],
    stats["meanResTime"],
    stats["minResTime"],
    stats["maxResTime"],
    stats["errorCount"],
    stats["sampleCount"],
    report_dir,
]
with open(runs_csv, "a", newline="", encoding="utf-8") as fh:
    csv.writer(fh).writerow(row)
PY
    done

    docker compose -f "${compose}" down -v >/dev/null 2>&1 || true
  )
done

python3 - "${RUNS_CSV}" "${SUMMARY_CSV}" "${SUMMARY_MD}" <<'PY'
import csv
import statistics
import sys

runs_csv, summary_csv, summary_md = sys.argv[1:]
rows = []
with open(runs_csv, newline="", encoding="utf-8") as fh:
    for row in csv.DictReader(fh):
        row["run"] = int(row["run"])
        for key in ("throughput", "avg_latency", "min_latency", "max_latency"):
            row[key] = float(row[key])
        for key in ("error_count", "sample_count"):
            row[key] = int(row[key])
        rows.append(row)

groups = {}
for row in rows:
    groups.setdefault(row["label"], []).append(row)

ranked = []
for label, items in groups.items():
    items.sort(key=lambda item: item["run"])
    throughputs = [item["throughput"] for item in items]
    avg_latency = [item["avg_latency"] for item in items]
    min_latency = [item["min_latency"] for item in items]
    max_latency = [item["max_latency"] for item in items]
    errors = sum(item["error_count"] for item in items)
    samples = sum(item["sample_count"] for item in items)
    success = samples - errors
    mean_tp = statistics.fmean(throughputs)
    variance_pct = 0.0
    if mean_tp:
        variance_pct = statistics.pstdev(throughputs) / mean_tp * 100.0
    ranked.append({
        "label": label,
        "avg_throughput": mean_tp,
        "avg_latency": statistics.fmean(avg_latency),
        "min_latency": statistics.fmean(min_latency),
        "max_latency": statistics.fmean(max_latency),
        "errors": errors,
        "samples": samples,
        "success": success,
        "success_pct": (success / samples * 100.0) if samples else 0.0,
        "variance_pct": variance_pct,
        "reports": [item["report_dir"] for item in items],
    })

ranked.sort(key=lambda item: item["avg_throughput"], reverse=True)

with open(summary_csv, "w", newline="", encoding="utf-8") as fh:
    writer = csv.writer(fh)
    writer.writerow([
        "rank", "label", "avg_throughput", "avg_latency", "min_latency", "max_latency",
        "errors", "samples", "success", "success_pct", "variance_pct", "report_dirs"
    ])
    for idx, item in enumerate(ranked, start=1):
        writer.writerow([
            idx,
            item["label"],
            f"{item['avg_throughput']:.1f}",
            f"{item['avg_latency']:.1f}",
            f"{item['min_latency']:.1f}",
            f"{item['max_latency']:.1f}",
            item["errors"],
            item["samples"],
            item["success"],
            f"{item['success_pct']:.2f}",
            f"{item['variance_pct']:.1f}",
            " | ".join(item["reports"]),
        ])

with open(summary_md, "w", encoding="utf-8") as fh:
    fh.write("| Rank | Configuration | Avg Throughput | Avg Latency | Min Latency | Max Latency | Success Rate | Variance |\\n")
    fh.write("|------|---------------|----------------|-------------|-------------|-------------|--------------|----------|\\n")
    for idx, item in enumerate(ranked, start=1):
        fh.write(
            f"| {idx} | {item['label']} | {item['avg_throughput']:.1f} emails/sec | "
            f"{item['avg_latency']:.1f} ms | {item['min_latency']:.1f} ms | {item['max_latency']:.1f} ms | "
            f"{item['success']}/{item['samples']} ({item['success_pct']:.2f}%) | ±{item['variance_pct']:.1f}% |\\n"
        )
PY

printf '\nRuns CSV: %s\n' "${RUNS_CSV}"
printf 'Summary CSV: %s\n' "${SUMMARY_CSV}"
printf 'Summary MD: %s\n' "${SUMMARY_MD}"
