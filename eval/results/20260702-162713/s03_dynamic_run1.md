# s03 | mode=dynamic | run=1 | 18.6s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod ends up in **`OOMKilled`** (in order of probability)

| # | Reason (why the container is killed) | How to verify it (when you can run `kubectl`) | How to fix it |
|---|--------------------------------------|-----------------------------------------------|---------------|
| **1** | **Memory limit is lower than the amount the container actually needs** – the kernel’s OOM‑killer terminates the container as soon as it hits the `limits.memory` value. | • `kubectl describe pod s03-oomkilled -n kubexplain-eval` – look for `State: Terminated` → `Reason: OOMKilled` and the *Events* section that may show “Container was killed due to exceeding memory limit”. <br>• `kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml` – inspect the `resources.limits.memory` field for the container. <br>• If you have metrics server installed: `kubectl top pod s03-oomkilled -n kubexplain-eval` – compare the **CURRENT** usage with the limit. | • Raise the memory limit (and optionally the request) in the pod spec. <br>• If the pod is managed by a Deployment/StatefulSet, edit the controller’s manifest and apply it. <br>• Re‑deploy the workload. |
| **2** | **Application has a memory leak or spikes under load** – even a correctly sized limit can be exceeded when the app gradually consumes more RAM. | • Same `kubectl top pod …` as above – you’ll see usage climbing until the kill. <br>• Check the container’s logs (`kubectl logs s03-oomkilled -n kubexplain-eval`) for patterns that precede the kill (e.g., “OutOfMemoryError”). <br>• If you have a monitoring stack (Prometheus, Grafana), look at the memory‑usage time‑series for the pod. | • Fix the leak or reduce the workload that triggers the spike. <br>• Add a higher limit as a temporary safety net, but the root fix is code‑level. |
| **3** | **Node is under memory pressure** – the kubelet may evict pods when the node’s available memory falls below the eviction threshold. The container is still killed with `OOMKilled` because the cgroup limit is hit. | • `kubectl describe node <node‑name>` – check the *Conditions* and *Events* for `MemoryPressure=True`. <br>• Look at the node’s `kubectl top node` output to see overall memory usage. | • Add more nodes or increase the node size. <br>• Reduce overall memory requests/limits of other workloads on the same node. <br>• Tune the kubelet eviction thresholds if you need a higher safety margin. |
| **4** | **Incorrect unit or typo in the limit** – e.g., `memory: "500"` (interpreted as 500 bytes) instead of `500Mi`. The container runs out of memory almost immediately. | • Inspect the pod YAML (`kubectl get pod … -o yaml`) – verify that the limit uses a proper unit (`Mi`, `Gi`, etc.). | • Correct the unit or value in the manifest and redeploy. |
| **5** | **No memory limit defined** – the container runs with the node’s default cgroup limit; if the node itself runs low on memory, the kernel may kill the container. | • In the pod YAML, absence of `resources.limits.memory`. <br>• Node‑level OOM events in `kubectl describe node` or in the node’s system logs (`/var/log/kubelet.log`). | • Add an explicit memory limit (and request) that reflects the workload’s needs. |
| **6** | **HugePages or other memory‑intensive features** – allocating hugepages can exhaust the node’s memory pool, causing OOM kills. | • Check the pod spec for `resources.limits.hugepages-2Mi` or similar. <br>• Look at node‑level hugepage usage (`kubectl describe node`). | • Reduce hugepage allocation or move the workload to a node with enough hugepages. |
| **7** | **Mis‑configured cgroup driver / runtime bug** – rare, but a mismatch between Docker/containerd and kubelet can cause the OOM‑killer to fire incorrectly. | • Examine the node’s kubelet logs (`/var/log/kubelet.log`) for messages about cgroup errors. | • Align the cgroup driver across the runtime and kubelet (e.g., both `systemd`). Upgrade to a newer, stable version of the runtime/kubelet. |

---

## How to verify each cause (once you regain cluster access)

Below are the concrete `kubectl` commands you can run **after you’re back in the cluster**. They are grouped by the cause they help confirm.

### 1️⃣ Verify memory limit vs. usage
```bash
kubectl describe pod s03-oomkilled -n kubexplain-eval
kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml
kubectl top pod s03-oomkilled -n kubexplain-eval
```

### 2️⃣ Look for a leak / spikes
```bash
kubectl logs s03-oomkilled -n kubexplain-eval --previous   # last terminated container
kubectl top pod s03-oomkilled -n kubexplain-eval --containers
# (If you have Prometheus) query: container_memory_working_set_bytes{pod="s03-oomkilled"}
```

### 3️⃣ Check node memory pressure
```bash
# First find the node the pod was on
kubectl get pod s03-oomkilled -n kubexplain-eval -o wide
# Then describe that node
kubectl describe node <node-name>
kubectl top node
```

### 4️⃣ Validate limit units
```bash
kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml | grep -A2 limits
```

### 5️⃣ Detect missing limits
Same as step 4 – if the `limits.memory` field is absent, the pod has no explicit cap.

### 6️⃣ HugePages usage
```bash
kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml | grep hugepages
kubectl describe node <node-name> | grep hugepages
```

### 7️⃣ Runtime / cgroup issues
```bash
# On the node (ssh in) – view kubelet logs
journalctl -u kubelet | grep -i cgroup
```

---

## Typical remediation workflow

1. **Gather evidence** – run the verification commands above.  
2. **Identify the root cause** – the most common pattern is “limit too low → OOMKilled”.  
3. **Update the manifest** – edit the Deployment/StatefulSet/Pod spec to adjust `resources.limits.memory` (and optionally `resources.requests.memory`).  
4. **Apply the change** – `kubectl apply -f <updated‑manifest.yaml>` (or `kubectl rollout restart deployment/<name>`).  
5. **Monitor** – after the new pods start, watch `kubectl top pod` and the pod’s events for a few cycles to confirm the OOM kills have stopped.  
6. **If the problem persists** – move to the next likely cause (leak, node pressure, etc.) and repeat the verification/fix steps.

---

### Quick “cheat‑sheet” for when you’re back online

| Goal | Command |
|------|---------|
| See why the container stopped | `kubectl describe pod s03-oomkilled -n kubexplain-eval` |
| Show current memory usage | `kubectl top pod s03-oomkilled -n kubexplain-eval` |
| View the pod’s full spec (limits/requests) | `kubectl get pod s03-oomkilled -n kubexplain-eval -o yaml` |
| Find the node the pod ran on | `kubectl get pod s03-oomkilled -n kubexplain-eval -o wide` |
| Check node memory pressure | `kubectl describe node <node>` |
| Look at previous container logs (may contain OOM traces) | `kubectl logs s03-oomkilled -n kubexplain-eval --previous` |

---

### Bottom line

- **Most probable:** the container’s memory limit is too low for its workload.  
- **Next most probable:** the application leaks memory or spikes under load.  
- **Less common but still possible:** node‑wide memory pressure, mis‑typed limits, missing limits, hugepage exhaustion, or runtime/cgroup bugs.

Address the first two items, then move down the list if the issue remains. Good luck when you regain access!
