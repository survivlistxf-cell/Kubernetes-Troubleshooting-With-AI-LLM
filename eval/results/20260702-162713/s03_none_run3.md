# s03 | mode=none | run=3 | 17.1s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod is being **OOMKilled** (ordered by how often they show up)

| # | Reason (why the container hit the kernel OOM killer) | How to verify when you get back to the cluster | Typical fix |
|---|------------------------------------------------------|-----------------------------------------------|-------------|
| 1 | **Memory limit is lower than the workload’s normal usage** – the container hits its cgroup limit and is killed. | • `kubectl describe pod s03-oomkilled -n kubexplain-eval` – look at the **Limits** section.<br>• `kubectl top pod s03-oomkilled -n kubexplain-eval` (needs metrics‑server) – compare **CURRENT** usage with the **LIMIT**.<br>• Check the pod’s **Events** for `OOMKilled` messages that include the limit value. | Raise the `memory` limit (and usually the request) to a value that comfortably covers the observed peak, e.g. +30 % margin. |
| 2 | **Memory leak or sudden spike in the application** – the process gradually consumes more RAM until it exceeds the limit. | • Enable a short‑term memory‑profile (e.g. `pprof`, `jcmd`, `heapdump`) and collect it before the next kill.<br>• Look at historic `kubectl top pod` data (Prometheus, Grafana) for a rising trend.<br>• Examine container logs for “OutOfMemoryError”, “GC overhead limit exceeded”, etc. | Fix the leak or reduce the peak (e.g., batch size, cache size). If the spike is legitimate, increase the limit or add a **HorizontalPodAutoscaler** that can spin up extra replicas during bursts. |
| 3 | **Missing or too‑small resource requests/limits** – a `LimitRange` or default limit may have been applied automatically, leaving the pod with a very low ceiling. | • `kubectl get limitrange -A` – see if a namespace‑wide default is in place.<br>• `kubectl describe pod s03-oomkilled` – note if the **Limits** fields are empty or show the default values.<br>• Check the **QoS class** (`kubectl get pod s03-oomkilled -o jsonpath='{.status.qosClass}'`). | Explicitly set appropriate `resources.requests.memory` and `resources.limits.memory` in the pod spec (or Deployment/StatefulSet). Avoid relying on defaults for production workloads. |
| 4 | **Node‑level memory pressure** – the node runs out of RAM and the kernel kills containers even if they are below their cgroup limit (rare for OOMKilled, but can happen when no limit is set). | • `kubectl describe node <node-name>` – look for **MemoryPressure** condition and recent **Eviction** events.<br>• `dmesg` or node system logs (if you have node access) for “Out of memory: Kill process …”. | Add more memory to the node pool, or spread the workload across more nodes (increase replica count, use pod anti‑affinity). Also set proper limits so the kernel can enforce per‑container caps. |
| 5 | **High OOMScoreAdj** – the container is given a higher OOM‑killer score, making it a preferred victim when the node is under pressure. | • `kubectl get pod s03-oomkilled -o jsonpath='{.spec.securityContext.oomScoreAdj}'` (or check the container‑level `securityContext`).<br>• Node events showing “Killed process … because of OOMScoreAdj”. | Remove or lower the `oomScoreAdj` value (default is 0). Only use a non‑zero value when you deliberately want a pod to be killed first. |
| 6 | **Side‑car or init‑container memory consumption** – another container in the same pod eats most of the pod’s memory, leaving too little for the main container. | • `kubectl top pod s03-oomkilled -n kubexplain-eval --containers` – see per‑container usage.<br>• `kubectl describe pod s03-oomkilled` – verify each container’s limits. | Give the side‑car its own, appropriately sized limits, or move the side‑car to a separate pod if it can run independently. |
| 7 | **HugePages or memory over‑commit settings** – the pod requests hugepages that are not available, or the node is configured with aggressive over‑commit, causing early OOM kills. | • `kubectl describe pod s03-oomkilled` – look for `resources.limits.hugepages-*`.<br>• `kubectl describe node <node>` – check `Allocatable` hugepages vs. `Capacity`.<br>• Node sysctl `vm.overcommit_memory` (requires node access). | Remove unnecessary hugepage requests or increase the node’s hugepage allocation. Adjust over‑commit settings only after understanding the impact. |

---

## How to verify each cause (once you have cluster access)

Below are the concrete `kubectl` commands you can run. Run them **after you reconnect to the cluster**.

```bash
# 1. Inspect the pod spec and events
kubectl describe pod s03-oomkilled -n kubexplain-eval
```

```bash
# 1. Current memory usage vs. limits (needs metrics‑server)
kubectl top pod s03-oomkilled -n kubexplain-eval
```

```bash
# 2. Look at historic metrics (Prometheus query example)
# (run in Prometheus UI or via promtool)
# sum by (pod) (container_memory_working_set_bytes{pod="s03-oomkilled",namespace="kubexplain-eval"})
```

```bash
# 3. Check for namespace‑wide LimitRanges
kubectl get limitrange -n kubexplain-eval -o yaml
```

```bash
# 4. Node memory pressure status
NODE=$(kubectl get pod s03-oomkilled -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
kubectl describe node $NODE | grep -i -A3 "MemoryPressure"
```

```bash
# 5. OOMScoreAdj (if set)
kubectl get pod s03-oomkilled -n kubexplain-eval -o jsonpath='{.spec.securityContext.oomScoreAdj}'
kubectl get pod s03-oomkilled -n kubexplain-eval -o jsonpath='{.spec.containers[*].securityContext.oomScoreAdj}'
```

```bash
# 6. Per‑container memory usage
kubectl top pod s03-oomkilled -n kubexplain-eval --containers
```

```bash
# 7. HugePages request/allocatable
kubectl describe pod s03-oomkilled -n kubexplain-eval | grep -i hugepages
kubectl describe node $NODE | grep -i hugepages
```

---

## General remediation checklist

1. **Raise memory limits** – add a safety margin (e.g., 20‑30 % above the observed peak).
2. **Add/adjust requests** – ensure the scheduler places the pod on a node with enough free RAM.
3. **Fix application memory usage** – profile, patch leaks, reduce cache/batch sizes.
4. **Enable autoscaling** – HorizontalPodAutoscaler (based on CPU/memory) or Cluster Autoscaler to add nodes when needed.
5. **Separate heavy side‑cars** – move them to their own pods if they compete for memory.
6. **Review LimitRanges & defaults** – set sensible defaults for the namespace so new pods aren’t unintentionally constrained.
7. **Monitor continuously** – set alerts on `container_memory_working_set_bytes` approaching the limit, and on node `MemoryPressure` conditions.

Apply the fixes that match the verification results you obtain. Once the pod stays healthy for a few restart cycles, the root cause is resolved. Good luck!
