# s03 | mode=none | run=2 | 11.5s | needs_search=0

**Q:** My pod 's03-oomkilled' in namespace 'kubexplain-eval' keeps getting killed and restarted. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** container exceeds memory limit (OOMKilled); raise limits.memory or reduce usage

---

## Most likely reasons a pod is being **OOMKilled** (ordered by probability)

1. **Memory limit is lower than the container’s actual usage**  
2. **Memory leak or sudden memory‑spike in the application**  
3. **Node‑level memory pressure causing the kubelet to evict pods**  
4. **Incorrect or missing resource **requests** causing the pod to be placed on a low‑memory node**  
5. **Quality‑of‑Service (QoS) class is *BestEffort* or *Burstable* and the node runs out of memory**  
6. **Side‑car or init‑container consumes memory and pushes the main container over its limit**  
7. **Mis‑configured `LimitRange` or `ResourceQuota` that forces a low limit on the pod**  
8. **Kernel OOM killer triggered by other system processes (rare for a single pod)**  

Below is how you can **verify** each hypothesis and the typical **fix** once you regain access to the cluster.

---

### 1. Memory limit lower than actual usage  

**Verify**  

```bash
kubectl -n kubexplain-eval describe pod s03-oomkilled
```

* Look for the **Limits** section under each container.  
* Check the **Events** for lines like `Killing container ... OOMKilled`.  

```bash
kubectl -n kubexplain-eval top pod s03-oomkilled
```

* The `MEMORY` column shows the current usage; compare it with the limit shown in the `describe` output.

**Fix**  

* Raise the memory limit (and usually the request) in the pod spec or its Deployment/StatefulSet, e.g.:

```yaml
resources:
  requests:
    memory: "512Mi"
  limits:
    memory: "1Gi"
```

* Apply the updated manifest (`kubectl apply -f <file>`).  
* If you use Helm, bump the values and run `helm upgrade`.

---

### 2. Memory leak or sudden spike in the application  

**Verify**  

* Pull recent logs to see if the app reports out‑of‑memory errors before the kill:

```bash
kubectl -n kubexplain-eval logs s03-oomkilled --previous
```

* If you have metrics (Prometheus, Metrics Server), query the memory usage over time for that pod to spot a rising trend.

**Fix**  

* Patch the application code to release memory, add proper GC, or limit cache sizes.  
* If the spike is occasional and you cannot change the code, increase the limit (see #1) or add a **HorizontalPodAutoscaler** with memory‑based scaling.

---

### 3. Node‑level memory pressure (node OOM)  

**Verify**  

```bash
kubectl -n kubexplain-eval get pod s03-oomkilled -o jsonpath='{.status.hostIP}'
```

* Note the node IP, then inspect the node:

```bash
kubectl describe node <node-name>
```

* Look for `MemoryPressure` condition or events like `Node <node> memory pressure`.  

* If you have node metrics, check total node memory usage.

**Fix**  

* Add more nodes or larger instance types to increase total memory.  
* Enable **cluster autoscaler** so new nodes are provisioned when memory pressure rises.  
* Taint the overloaded node and migrate the pod to a healthier node (e.g., by deleting the pod; the controller will recreate it elsewhere).

---

### 4. Missing or too‑low **requests** causing bad scheduling  

**Verify**  

* In the `describe pod` output, see the **Requests** section. If it’s `0` or very low, the pod may have been scheduled onto a node that cannot sustain its runtime memory needs.

**Fix**  

* Define sensible `requests.memory` (usually equal to or slightly lower than the limit).  
* Re‑apply the manifest so the scheduler places the pod on a node with enough allocatable memory.

---

### 5. QoS class is *BestEffort* or *Burstable*  

**Verify**  

* `kubectl get pod s03-oomkilled -o jsonpath='{.status.qosClass}'`  

* If the result is `BestEffort` or `Burstable`, the pod is more vulnerable to node‑level OOM.

**Fix**  

* Provide both **requests** and **limits** (as in #1) to promote the pod to `Guaranteed` QoS.  
* This also gives the kubelet clearer guidance on eviction priority.

---

### 6. Side‑car or init‑container consumes memory  

**Verify**  

* `kubectl -n kubexplain-eval describe pod s03-oomkilled` – check the **Containers** list for any side‑cars or init‑containers and their resource specs.  

* Look at the **Events** for which container was killed (`container <name>`).

**Fix**  

* Add or increase memory limits for the offending side‑car/init‑container.  
* If the side‑car isn’t essential, consider removing it or moving its workload to a separate pod.

---

### 7. `LimitRange` or `ResourceQuota` forcing low limits  

**Verify**  

```bash
kubectl -n kubexplain-eval get limitrange
kubectl -n kubexplain-eval get resourcequota
```

* Inspect the `spec.limits` or `spec.hard` fields for memory caps that might be lower than what you intended.

**Fix**  

* Adjust the `LimitRange`/`ResourceQuota` to allow higher memory limits, or override them in the pod spec (if the namespace permits).  
* Re‑apply the updated objects.

---

### 8. Kernel OOM killer triggered by other processes  

**Verify**  

* On the node, check the system journal (requires node access) for lines like `Out of memory: Kill process ...`.  

* If you see unrelated processes being killed, the node itself is starved.

**Fix**  

* Same as #3 – add capacity, enable autoscaling, or move other heavy workloads off the node.  

---

## Quick checklist you can run when you’re back in the cluster

1. `kubectl -n kubexplain-eval describe pod s03-oomkilled` – look for OOM events, limits, QoS.  
2. `kubectl -n kubexplain-eval top pod s03-oomkilled` – compare usage vs limit.  
3. `kubectl -n kubexplain-eval logs s03-oomkilled --previous` – see app‑level OOM messages.  
4. Identify the node (`.status.hostIP`) and inspect it for memory pressure.  
5. Verify `LimitRange` and `ResourceQuota` in the namespace.  
6. Adjust resources, QoS, or node capacity based on what you discover.

Address the first cause that matches your observations; most OOMKilled pods are simply running with a limit that’s too low for their real memory demand. Once the limits are raised (or the application memory usage is reduced), the rest of the symptoms usually disappear. Good luck!
