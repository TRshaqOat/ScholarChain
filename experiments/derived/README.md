# Derived tables (Phase 15 work order 4 A5)

Regenerate: `python3 -I experiments/analysis/derive-tables.py`. Each directory's README.md names its sources and their sha256 (also sources.csv) and lists its gaps (also gaps.csv).

| directory | content |
|---|---|
| size-scaling/ | EMR pipeline runs at 25, 50 and 100 percent (Hadoop and Spark step minutes, accepted works) |
| exp-a/ | experiment A: gap report only |
| exp-d/ | experiment D levels, per kind, and runs |
| exp-d-scale/ | experiment D-scale level, scaling timeline, intervals, throughput by task count, steady-state comparison and caveats, metrics per minute |
| exp-f/ | experiment F checks, runs, queue depths |

## All gaps

- size_scaling: missing source: operations/pipeline-runs/20261116T063500Z-fraction25/run.json
- size_scaling: no raw results under experiments/results/exp-a*; experiment A's run is the deef295 25 percent pipeline record in size-scaling (operations/pipeline-runs/20261117T045335Z-fraction25), not an experiments/results run
- exp_d_scale: metric alb_target_5xx: no datapoints in the interval
