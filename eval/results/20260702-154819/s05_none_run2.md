# s05 | mode=none | run=2 | 8.4s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## What the `describe` output tells us

| Section | What it shows | Why it matters |
|---------|---------------|----------------|
| **Requests** (cpu 500, memory 1000Gi) | The pod asks for **0.5 CPU** and **1 TiB of RAM**. | No node in a typical cluster has 1 TiB of allocatable memory, so the scheduler immediately marks the pod unschedulable for *Insufficient memory*. |
| **Events – FailedScheduling** | `0/6 nodes are available: 2 Insufficient cpu, 2 Insufficient memory, 1 node(s) had untolerated taint {dedicated: ollama}, 3 node(s) had untolerated taint {node‑role.kubernetes.io/control‑plane: }` | In addition to the massive memory request, the pod does **not tolerate** two taints that are present on the nodes: <br>• `dedicated=ollama` <br>• `node-role.kubernetes.io/control-plane` | The scheduler will never place the pod on those nodes unless the pod explicitly tolerates the taints. |
| **Tolerations** | Only the generic `node.kubernetes.io/not‑ready` and `node.kubernetes.io/unreachable` tolerations are present. | No toleration for the two taints mentioned above, so those nodes are filtered out. |

### Root cause

1. **Unrealistic memory request** – `1000Gi` far exceeds the allocatable memory on any node, causing “Insufficient memory” failures.  
2. **Missing tolerations** – the pod does not tolerate the `dedicated=ollama` and `node-role.kubernetes.io/control-plane` taints that exist on the cluster’s nodes, so those nodes are excluded from scheduling.  
3. (Secondary) **CPU request** – `500` (interpreted as 500 mCPU) is modest, but on the few nodes that still have capacity it may also be insufficient when combined with the huge memory request.

## How to fix it

### 1. Reduce the resource requests to realistic values
```yaml
resources:
  requests:
    cpu: "100m"          # or whatever the container really needs
    memory: "128Mi"      # typical for a tiny busybox sleep
  limits:
    cpu: "200m"
    memory: "256Mi"
```
*Why*: With a realistic memory request the scheduler can find a node that satisfies the pod’s needs.

### 2. Add tolerations (if you really need to run on those tainted nodes)

If you intend the pod to be allowed on nodes that carry the `dedicated=ollama` or the control‑plane taint, add tolerations:

```yaml
tolerations:
- key: "dedicated"
  operator: "Equal"
  value: "ollama"
  effect: "NoSchedule"
- key: "node-role.kubernetes.io/control-plane"
  operator: "Exists"
  effect: "NoSchedule"
```

*Why*: This tells the scheduler that the pod is okay with those taints, making the nodes eligible for placement.

### 3. Choose one of the two approaches above

- **If the pod does not need to run on the tainted nodes** (most common for a simple test pod), just **remove the large memory request** and **leave the tolerations unchanged**. The scheduler will then place the pod on any regular worker node that has enough CPU/memory.
- **If the pod must run on a specific tainted node** (e.g., a node dedicated to a special workload), keep the reduced resource request **and** add the appropriate tolerations.

### 4. Verify the fix

After updating the pod spec (e.g., via `kubectl apply -f <manifest>.yaml` or by editing the pod), watch the pod status:

```bash
kubectl get pod s05-pending -n kubexplain-eval -w
```

The pod should transition from **Pending** → **Running** once a suitable node is found.

---

### Quick checklist

- [ ] **Memory request** ≤ the largest allocatable memory on any node (e.g., ≤ 2Gi for a 4Gi node).  
- [ ] **CPU request** ≤ available CPU on at least one node.  
- [ ] **Tolerations** match any taints present on the nodes you want the pod to run on.  
- [ ] **Apply** the corrected manifest and confirm the pod schedules.

Following these steps will resolve the “Pending” state and get `s05-pending` running.
