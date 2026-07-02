# s03 | mode=none | run=2 | 6.8s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it?

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

### What is happening?

| Observation | Meaning |
|-------------|---------|
| **Command** `head -c 512m /dev/zero | tail` | The container tries to read **512 MiB** of data into memory. |
| **Resources**<br>‑ **limit:** 64 Mi<br>‑ **request:** 32 Mi | The pod is allowed to use at most **64 Mi** of RAM. |
| **Last State** – **Reason:** `OOMKilled`<br>**Exit Code:** 137 | The Linux OOM‑killer terminated the process because it exceeded the container’s memory limit. |
| **State** – **Reason:** `CrashLoopBackOff` | After being killed, kubelet keeps trying to restart the container, backing off after each failure. |
| **QoS Class:** `Burstable` | With a limit set, the container is subject to OOM‑kill when it goes over that limit. |

**Root cause:** The workload deliberately allocates ~512 MiB, but the pod’s memory **limit is only 64 Mi**. When the process exceeds the limit, the kernel kills it (OOMKilled), causing the pod to enter a CrashLoopBackOff.

---

### How to fix it

#### 1. Raise the memory limit (quick test)

```bash
kubectl edit pod s03-oomkilled -n kubexplain-eval
```

* In the editor, change the `resources.limits.memory` (and optionally `requests.memory`) to a value larger than the amount the container needs, e.g.:

```yaml
resources:
  limits:
    memory: 1Gi
  requests:
    memory: 512Mi
```

* Save and exit. The pod will be recreated with the new limits and should stay running.

#### 2. Adjust the workload instead of the limits

If the pod is only meant to demonstrate OOM behavior, reduce the amount of memory it tries to allocate:

```yaml
command:
- sh
- -c
- head -c 64m /dev/zero | tail   # fits within a 64Mi limit
```

Or use a more realistic workload that respects the limits.

#### 3. Remove the limit (if you really want the container unrestricted)

```yaml
resources:
  limits: {}
  requests: {}
```

*Be aware*: Without a limit the container can consume all node memory, potentially affecting other pods.

#### 4. Verify the fix

```bash
kubectl get pod s03-oomkilled -n kubexplain-eval -o wide
kubectl describe pod s03-oomkilled -n kubexplain-eval
```

* Look for `State: Running` and `Ready: True`.  
* Ensure no new `OOMKilled` events appear.

---

### TL;DR

*The container is killed because it tries to use ~512 MiB while the pod’s memory limit is only 64 MiB.*  
Fix it by **increasing the memory limit** (or request) to exceed the workload’s needs, or **reduce the workload’s memory consumption**. After applying the change, the pod should stay in the `Running`/`Ready` state without entering `CrashLoopBackOff`.
