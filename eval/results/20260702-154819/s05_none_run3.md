# s05 | mode=none | run=3 | 9.1s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### What the `kubectl describe pod` output is telling you  

| Observation | What it means |
|-------------|---------------|
| **Pod status = Pending** and **PodScheduled = False** | The scheduler could not find a node that satisfies the pod’s requirements. |
| **Events – FailedScheduling** | The scheduler tried every node and rejected all of them for the following reasons: |
| • `1 node(s) had untolerated taint {dedicated: ollama}` | One node is marked with a custom taint `dedicated=ollama`. Your pod does **not** have a matching toleration, so the scheduler will never place the pod there. |
| • `3 node(s) had untolerated taint {node‑role.kubernetes.io/control‑plane: }` | Control‑plane nodes are tainted by default (`node-role.kubernetes.io/control-plane`). Your pod does not tolerate that taint, so those nodes are also excluded. |
| • `2 Insufficient cpu` / `2 Insufficient memory` | On the remaining nodes the amount of **available** CPU and **memory** is lower than what the pod requests. |
| • `preemption: … No preemption victims found … Preemption is not helpful` | Even if the scheduler tried to evict lower‑priority pods, it still couldn’t make enough room for this pod. |

#### The pod’s resource request is the biggest blocker  

```text
Requests:
  cpu:    500          # interpreted as 500 m (0.5 CPU)
  memory: 1000Gi      # 1 TiB of RAM
```

A request of **1 TiB** of memory is far beyond what any typical node provides, so every node reports “Insufficient memory”. The CPU request (0.5 CPU) is modest, but it is irrelevant while the memory request cannot be satisfied.

### Root cause  

1. **Unrealistic memory request** (`1000Gi`). No node in the cluster has that much free RAM.  
2. **Untolerated taints** on all nodes (`dedicated=ollama` and the default control‑plane taint).  
3. Consequently, the scheduler cannot place the pod, and preemption does not help because the memory shortfall is too large.

### How to fix it  

You have three independent levers:

#### 1. Reduce the pod’s resource requests (recommended)  
- Lower the memory request to a realistic value that matches the capacity of your nodes (e.g., `256Mi`, `512Mi`, or whatever your workload actually needs).  
- Keep the CPU request if 0.5 CPU is appropriate, or adjust it as well.

#### 2. Add tolerations (if you really want the pod to run on those tainted nodes)  
- If the node(s) with `dedicated=ollama` are intended for this workload, add a toleration for that taint.  
- If you are okay running on control‑plane nodes (not typical for production), add a toleration for `node-role.kubernetes.io/control-plane`.

#### 3. Provide enough capacity (or change the node pool)  
- Add new worker nodes with sufficient RAM to satisfy the request (only makes sense if the large memory request is intentional).  
- Or remove/adjust the taints on existing nodes if they are unnecessarily restricting scheduling.

### Concrete steps (you can pick the combination that fits your environment)

#### A. Edit the pod (or its Deployment/Job/ReplicaSet) to lower memory

```bash
kubectl edit pod s05-pending -n kubexplain-eval
```

- Change the `resources.requests.memory` field from `1000Gi` to something like `256Mi` (or the amount you really need).  
- Save and exit. The pod will be recreated (or you can delete it and let the controller create a new one).

#### B. Add tolerations (if you keep the current node taints)

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

- Add the above block to the pod spec (or the higher‑level controller).  
- Apply the updated manifest.

#### C. Scale the cluster (if the large memory request is intentional)

- Provision additional worker nodes with at least the requested memory (e.g., a node with ≥ 1 TiB free RAM).  
- Ensure those nodes do **not** have taints that the pod cannot tolerate.

### Quick verification after you make changes  

```bash
kubectl get pod s05-pending -n kubexplain-eval -o wide
kubectl describe pod s05-pending -n kubexplain-eval
```

- The `STATUS` should transition from **Pending** to **Running**.  
- The `Events` section should no longer show `FailedScheduling` messages.

### Summary  

- **Root cause:** The pod asks for 1000 GiB of memory, which no node can provide, and it also lacks tolerations for the taints present on every node.  
- **Fix:** Reduce the memory request to a realistic size, optionally add tolerations for the existing taints, or add nodes with enough capacity. Once the request fits the available resources (and any needed tolerations are present), the scheduler will bind the pod and it will start running.
