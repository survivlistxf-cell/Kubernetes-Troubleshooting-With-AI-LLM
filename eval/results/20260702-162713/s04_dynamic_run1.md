# s04 | mode=dynamic | run=1 | 11.9s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons why a **running** pod is slow or unresponsive  
*(ordered from highest to lowest probability)*  

### 1. CPU throttling / resource limits  
**Why it happens**  
- The pod has a **CPU request/limit** that is lower than what the application needs.  
- When the container tries to use more CPU than its limit, the kernel throttles it, causing the app to run very slowly.  

**How to verify (when you regain access)**  
- Check the pod’s resource spec:  

```bash
kubectl get pod s04-cpu-throttle -n kubexplain-eval -o yaml | grep -A3 resources
```  

- Look at the **CPU usage** vs. limit in the metrics API (if metrics‑server is installed):  

```bash
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```  

- Examine the pod events for messages like *“CPUThrottlingHigh”* or *“Container was throttled”* (shown by `kubectl describe pod`).  

**Fix**  
- Raise the **CPU request** (to guarantee scheduling) and/or the **CPU limit** (to allow bursts). Example:  

```yaml
resources:
  requests:
    cpu: "500m"
  limits:
    cpu: "1500m"
```  

- Apply the updated manifest (`kubectl apply -f …`) or edit the pod/Deployment (`kubectl edit deployment …`).  
- If the pod is part of a HorizontalPodAutoscaler, ensure the HPA can scale up when CPU usage is high.  

---

### 2. Memory pressure / OOM‑related throttling  
**Why it happens**  
- The container is close to its memory limit, causing the kernel to swap or the pod to be evicted/restarted. Even before OOM kills, the system may slow down due to paging.  

**How to verify**  
- Inspect memory usage:  

```bash
kubectl top pod s04-cpu-throttle -n kubexplain-eval
```  

- Look for **OOMKilled** or *“MemoryPressure”* events in `kubectl describe pod`.  

**Fix**  
- Increase the memory request/limit.  
- If the app can be tuned to use less memory (e.g., JVM heap settings), adjust those parameters.  

---

### 3. Node‑level resource contention (CPU/Memory pressure on the node)  
**Why it happens**  
- The node hosting the pod is overloaded by other workloads, causing the kubelet to throttle all containers on that node.  

**How to verify**  
- Identify the node: `kubectl get pod s04-cpu-throttle -n kubexplain-eval -o wide`.  
- Check node metrics:  

```bash
kubectl top node <node-name>
```  

- Review node conditions (`kubectl describe node <node-name>`) for *MemoryPressure*, *DiskPressure*, or *PIDPressure*.  

**Fix**  
- Add more nodes or rebalance workloads (e.g., adjust pod anti‑affinity, use taints/tolerations).  
- If the node is a spot/preemptible instance, consider switching to a more stable instance type.  

---

### 4. Network latency or connectivity problems  
**Why it happens**  
- The application depends on external services (databases, APIs) that are slow or unreachable.  
- Mis‑configured Service/Ingress, DNS failures, or network policies blocking traffic can also manifest as “slow” behavior.  

**How to verify**  
- Exec into the pod (once you have access) and run simple connectivity checks:  

```bash
kubectl exec -n kubexplain-eval -it s04-cpu-throttle -- ping <service-host>
kubectl exec -n kubexplain-eval -it s04-cpu-throttle -- curl -v http://<service>:<port>/healthz
```  

- Look at the pod’s **events** for *“Failed to resolve host”* or *“NetworkPolicy denies traffic”*.  
- Check the Service and Endpoints objects for the target service (`kubectl get svc,ep -n <ns>`).  

**Fix**  
- Resolve DNS or Service mis‑configurations.  
- Adjust or remove overly restrictive NetworkPolicies.  
- Verify that dependent services are healthy and not overloaded.  

---

### 5. Application‑level misconfiguration or bugs  
**Why it happens**  
- The container may be running with wrong environment variables, command‑line flags, or config files that cause the app to run in a degraded mode (e.g., debug logging, low thread pool size).  

**How to verify**  
- Compare the **running pod spec** with the intended configuration (`kubectl get pod … -o yaml`).  
- Look at the container logs for warnings or errors:  

```bash
kubectl logs -n kubexplain-eval s04-cpu-throttle
```  

- If the app exposes metrics (Prometheus, /metrics endpoint), inspect them for request latency, thread pool saturation, etc.  

**Fix**  
- Update the Deployment/StatefulSet manifest with the correct config values.  
- Redeploy the pod after correcting the image tag or config map/secret.  

---

### 6. Disk I/O bottleneck or storage issues  
**Why it happens**  
- The pod uses a PersistentVolume that is slow (e.g., network‑attached storage with high latency) or is close to capacity, causing the app to block on reads/writes.  

**How to verify**  
- Check PVC status: `kubectl get pvc -n kubexplain-eval`.  
- Inside the pod, run `iostat` or `df -h` (if the utilities are present) to see I/O wait or low free space.  

**Fix**  
- Move to a faster storage class, increase provisioned IOPS, or clean up disk usage.  

---

### 7. Pod is being repeatedly restarted or in a CrashLoopBackOff (but still shows *Running*)  
**Why it happens**  
- A side‑car container may be crashing, causing the main container to be starved of resources or to experience delays.  

**How to verify**  
- `kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – look for *Restart Count* on each container.  
- Check logs of the side‑car container.  

**Fix**  
- Fix the failing side‑car, or remove it if not needed.  

---

## Quick verification checklist (run when you can access the cluster)

1. **Pod spec & resources** – `kubectl get pod … -o yaml` → look at `resources`.  
2. **Live metrics** – `kubectl top pod …` and `kubectl top node …`.  
3. **Events** – `kubectl describe pod …` → scroll to *Events*.  
4. **Logs** – `kubectl logs …` (both containers if multi‑container).  
5. **Node health** – `kubectl describe node <node>` → conditions & pressure.  
6. **Network** – `kubectl exec … -- curl …` or `nslookup`.  
7. **Storage** – `kubectl get pvc …` and in‑pod `df -h` / `iostat`.  

Address the first cause that shows a concrete problem; most performance‑related issues in a *Running* pod are due to **CPU throttling** or **node‑level contention**. Once those are ruled out, move down the list.
