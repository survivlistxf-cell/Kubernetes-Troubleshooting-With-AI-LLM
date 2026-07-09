# s04 | mode=static | run=2 | 17.7s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons a **Running** pod is “slow” or un‑responsive  

| # | Likely cause (most to least probable) | How to verify (when you can run `kubectl`) | Typical fix |
|---|----------------------------------------|--------------------------------------------|-------------|
| 1 | **CPU throttling because the pod’s CPU **limit** is too low** (or the node is already saturated) | <ul><li>`kubectl top pod s04-cpu-throttle -n kubexplain-eval` – look at **CPU %** and **CPU (cores)**. If the pod is constantly at the limit (e.g. 100 % of its request) and the **CPU %** column shows a lot of **throttled** time, the container is being throttled.</li><li>`kubectl describe pod s04-cpu-throttle -n kubexplain-eval` – check the **Events** for messages like *“CPUThrottlingHigh”* or *“Container was throttled”*.</li><li>On the node, run `cat /sys/fs/cgroup/.../cpu.stat` (or use `kubectl exec` into a privileged pod) and look for a high **throttled_time** value.</li></ul> | Raise the **CPU request** and **limit** in the pod spec, or remove the limit if you can tolerate burstable usage. If the node itself is saturated, consider **scaling the node pool** or **pod‑affinity** to schedule the pod onto a less‑loaded node. |
| 2 | **Insufficient memory request → pod runs in the *Burstable* QoS class and competes with other pods** (possible GC pauses, OOM‑related slowdowns) | <ul><li>`kubectl top pod s04-cpu-throttle -n kubexplain-eval` – check **MEM %** and **MEM (usage)**. If the pod is near its **memory limit** you’ll see high usage.</li><li>`kubectl describe pod …` – look for **OOMKilled** events or *“MemoryPressure”* warnings on the node.</li><li>Inspect the container’s logs for GC pauses or “out of memory” messages.</li></ul> | Increase the **memory request** (and optionally the limit) so the pod gets a **Guaranteed** QoS class. If the node is under memory pressure, add more nodes or move the pod with a **node selector** / **affinity** to a node with more free RAM. |
| 3 | **Node‑level resource pressure (CPU steal, disk I/O, or memory pressure)** – the pod itself is fine but the underlying VM/host is overloaded | <ul><li>`kubectl describe node <node‑name>` – check **Conditions** (`MemoryPressure`, `DiskPressure`, `PIDPressure`) and recent **Events**.</li><li>`kubectl top node <node‑name>` – high **CPU %**, high **CPU (cores)**, or high **disk I/O** (if metrics server provides it).</li></ul> | Move the pod to a healthier node (add a **nodeSelector**, **taints/tolerations**, or **pod anti‑affinity**). If the whole cluster is saturated, **scale the node pool** or **upgrade the instance type**. |
| 4 | **Network latency or connectivity problems** (e.g., DNS failures, Service mesh side‑car slowdown, NetworkPolicy blocking traffic) | <ul><li>`kubectl exec -it s04-cpu-throttle -n kubexplain-eval -- ping <service‑name>` – see latency or time‑outs.</li><li>`kubectl exec … -- nslookup <service‑name>` – verify DNS resolution.</li><li>Check **NetworkPolicy** objects that affect the pod: `kubectl get networkpolicy -n kubexplain-eval`.</li></ul> | Fix DNS (ensure `kube-dns`/`CoreDNS` pods are healthy). Adjust or remove restrictive **NetworkPolicy**. If using a service mesh, verify side‑car health and consider disabling it temporarily to isolate the issue. |
| 5 | **Storage I/O bottleneck** (slow PVC, high latency on the underlying volume) | <ul><li>`kubectl exec … -- iostat -xz 1 5` (or `cat /proc/diskstats`) inside the pod to see high **await** or **%util**.</li><li>`kubectl describe pvc <pvc‑name>` – look for events like *“VolumeProvisioningFailed”* or *“VolumeMountFailed”.*</li></ul> | Move the pod to a node with faster storage, switch to a higher‑performance **StorageClass**, or add a **ReadWriteMany** volume that is less contended. |
| 6 | **Application‑level mis‑configuration** (wrong config map, missing env var, bad connection string) | <ul><li>Inspect the pod’s **environment**: `kubectl exec … -- env | grep <key>`.</li><li>Compare the live pod spec with the intended spec: `kubectl get pod s04-cpu-throttle -n kubexplain-eval -o yaml > live.yaml` and diff against your source.</li></ul> | Correct the ConfigMap/Secret, update the deployment, and roll out a new version (`kubectl rollout restart deployment …`). |
| 7 | **Side‑car or init‑container still running / stuck** (e.g., a logging side‑car consuming CPU) | <ul><li>`kubectl describe pod …` – look at **Container Statuses** for any non‑ready side‑cars.</li><li>`kubectl logs s04-cpu-throttle -c <sidecar‑name> -n kubexplain-eval` – see if it’s looping or error‑ing.</li></ul> | Fix the side‑car image or configuration, or temporarily remove it to see if the main container performance improves. |
| 8 | **Pod is being repeatedly restarted (CrashLoopBackOff) but still shows “Running” because a new container is up** – the app may be unhealthy in the current container | <ul><li>`kubectl get pod s04-cpu-throttle -n kubexplain-eval` – check **RESTARTS** column.</li><li>`kubectl logs s04-cpu-throttle -n kubexplain-eval` – look for error messages.</li></ul> | Resolve the crash cause (missing binary, bad command, insufficient resources) and redeploy. |

---

## How to proceed when you *do* regain cluster access  

1. **Collect quick metrics**  
   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   kubectl top node $(kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   ```

2. **Inspect the pod definition and events**  
   ```bash
   kubectl describe pod s04-cpu-throttle -n kubexplain-eval
   kubectl get pod s04-cpu-throttle -n kubexplain-eval -o yaml > live.yaml
   ```

3. **Check node health**  
   ```bash
   kubectl describe node $(kubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   ```

4. **Validate networking** (if the app talks to other services)  
   ```bash
   kubectl exec -it s04-cpu-throttle -n kubexplain-eval -- ping <service-name>
   kubectl exec -it s04-cpu-throttle -n kubexplain-eval -- nslookup <service-name>
   ```

5. **Look at container logs** for clues (GC pauses, errors, OOM, etc.)  
   ```bash
   kubectl logs s04-cpu-throttle -n kubexplain-eval
   ```

6. **Adjust resources** if CPU throttling or memory pressure is confirmed  
   ```yaml
   # Example snippet to increase limits (apply via kubectl apply -f)
   resources:
     requests:
       cpu: "500m"
       memory: "256Mi"
     limits:
       cpu: "1000m"
       memory: "512Mi"
   ```

   Then roll out the change (`kubectl rollout restart deployment <name>`).

7. **If node‑level pressure is the root cause**, consider:
   * Adding more nodes or larger instance types.
   * Using **PodAntiAffinity** to spread pods.
   * Tainting the overloaded node and letting the scheduler move the pod.

8. **If networking or storage is the bottleneck**, verify the health of CoreDNS, the relevant Service, NetworkPolicies, and the PVC’s underlying storage class.

---

### Quick “check‑list” you can run later

- **CPU**: `kubectl top pod …` → high % → raise CPU request/limit.  
- **Memory**: `kubectl top pod …` → near limit → raise memory request/limit.  
- **Node pressure**: `kubectl describe node …` → MemoryPressure/DiskPressure → rebalance or scale nodes.  
- **Events**: `kubectl describe pod …` → look for *Throttling*, *OOMKilled*, *NetworkPolicy* denials.  
- **Logs**: `kubectl logs …` → application errors, GC pauses, connection time‑outs.  
- **Network**: `kubectl exec … -- ping / nslookup` → DNS or connectivity latency.  
- **Storage**: `kubectl exec … -- iostat` or check PVC events → high I/O latency.

Address the first cause that shows up; most performance‑related complaints in a Running pod are due to **CPU throttling** or **memory pressure**, so start there. Once the resource limits are appropriate and the node is healthy, the application should return to normal responsiveness.
