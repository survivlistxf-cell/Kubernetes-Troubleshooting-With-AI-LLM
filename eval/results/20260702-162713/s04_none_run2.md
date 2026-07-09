# s04 | mode=none | run=2 | 19.8s | needs_search=0

**Q:** My pod 's04-cpu-throttle' in namespace 'kubexplain-eval' is running but the application inside is extremely slow and unresponsive. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** cpu limit far too low (25m) causes heavy throttling; raise limits.cpu

---

## Most likely reasons a “Running” pod is still painfully slow  

| Rank | Likely cause | Why it shows up as “Running” but is sluggish | How to verify (when you can run `kubectl`) | Typical fix |
|------|--------------|----------------------------------------------|--------------------------------------------|-------------|
| 1 | **CPU throttling – limits too low** | The pod is scheduled, but the container’s CPU **limit** is lower than the **request** or the workload’s actual need. The kernel will repeatedly throttle the cgroup, so the process makes progress only when the scheduler gives it a slice. | ```bash\nkubectl top pod s04-cpu-throttle -n kubexplain-eval\nkubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -i cpu\n```<br>Look for “CPU %” near 100 % while the pod’s **CPU Requests** are lower than the **Limits** (or limits are missing). The `kubectl top` output will show a high **CPU %** and a low **CPU (cores)** value, indicating throttling. | • Raise the **CPU limit** (or remove it) so the container can use more cores.<br>• Increase the **CPU request** to give the scheduler a better fit on a node with enough free CPU.<br>• If the node itself is saturated, move the pod to a less‑loaded node or add more nodes. |
| 2 | **Memory pressure – limit too low or OOM‑related GC pauses** | When a container hits its memory **limit**, the kernel may start killing pages or the runtime may trigger aggressive garbage collection, both of which dramatically slow the app. The pod stays “Running” because it hasn’t been killed outright. | ```bash\nkubectl top pod s04-cpu-throttle -n kubexplain-eval\nkubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -i memory\nkubectl logs s04-cpu-throttle -n kubexplain-eval | grep -i OOM\n```<br>High **Memory %** with usage close to the limit, or repeated “OOMKilled” events in the pod’s description, are red flags. | • Increase the **memory limit** (and request) to give the app headroom.<br>• Tune the application’s heap / cache sizes.<br>• If the node is out of memory, add capacity or enable **vertical pod autoscaling**. |
| 3 | **Node‑level resource saturation** (CPU, memory, disk I/O) | Even if the pod’s own limits are generous, the underlying node may be overloaded (e.g., many other pods, system daemons, or heavy background jobs). The scheduler can’t move the pod because it’s already bound, so the pod experiences contention. | ```bash\nkubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.spec.nodeName}'\nkubectl top node <node-name>\n```<br>Check the node’s **CPU %**, **Memory %**, and **disk I/O** (if you have metrics). High values indicate the node is a bottleneck. | • Evict or reschedule other heavy workloads.<br>• Add more nodes or enable **cluster autoscaler**.<br>• Use **node selectors / taints** to place the pod on a less‑busy node. |
| 4 | **Network latency / DNS problems** | If the app talks to external services, databases, or other pods, a broken Service, mis‑routed traffic, or DNS lookup delays can make the whole pod appear hung while the container itself is fine. | ```bash\nkubectl exec -n kubexplain-eval s04-cpu-throttle -- curl -s -o /dev/null -w \"%{time_total}\\n\" http://<service>\nkubectl exec -n kubexplain-eval s04-cpu-throttle -- nslookup <service>\n```<br>Long response times or DNS timeouts point to network/DNS issues. | • Verify Service endpoints (`kubectl get endpoints …`).<br>• Check CoreDNS logs for errors.<br>• Ensure NetworkPolicy isn’t unintentionally blocking traffic.<br>• If the problem is external, check firewall / VPC routes. |
| 5 | **Ephemeral‑storage throttling** | Pods that write logs, temp files, or use a volume may hit their **ephemeral‑storage** limit, causing the kernel to block writes. The container stays alive but I/O calls stall. | ```bash\nkubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -i storage\nkubectl exec -n kubexplain-eval s04-cpu-throttle -- df -h /tmp\n```<br>“Storage Pressure” events or a full `/tmp` filesystem indicate throttling. | • Raise the **ephemeral‑storage request/limit**.<br>• Clean up old files or rotate logs.<br>• Move heavy I/O to a PersistentVolume with sufficient capacity. |
| 6 | **Application‑level bottlenecks** (thread deadlock, GC spikes, mis‑configuration) | The container may be perfectly provisioned, but the code itself can become CPU‑bound (e.g., a tight loop) or spend most of its time in GC, waiting on locks, or retrying failed calls. | ```bash\nkubectl logs s04-cpu-throttle -n kubexplain-eval --tail=100\nkubectl exec -n kubexplain-eval s04-cpu-throttle -- jstack <pid>\n```<br>Look for repeated GC logs, stack traces stuck in the same method, or error bursts. | • Profile the application (e.g., Java Flight Recorder, pprof).<br>• Tune GC parameters or increase heap size.<br>• Fix deadlocks or excessive retries in the code.<br>• Deploy a newer, optimized version. |
| 7 | **Pod Quality‑of‑Service (QoS) downgrade** | If the pod’s **requests** are omitted or set to zero, it gets a **BestEffort** QoS class. On a busy node the kubelet may throttle it more aggressively than a **Burstable** pod. | ```bash\nkubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.status.qosClass}'\n```<br>“BestEffort” is a warning sign when the node is under pressure. | • Add sensible **requests** (CPU & memory) so the pod becomes **Burstable** or **Guaranteed**.<br>• Adjust node resource allocation accordingly. |
| 8 | **Security‑policy restrictions (AppArmor/SELinux)** | Over‑restrictive policies can cause syscalls to be denied, leading to hidden retries or slow fall‑backs inside the app. | ```bash\nkubectl exec -n kubexplain-eval s04-cpu-throttle -- dmesg | grep -i denied\n```<br>Audit messages about denied syscalls indicate a policy problem. | • Relax the offending profile or add the required capabilities.<br>• Re‑deploy the pod with an updated `securityContext`. |
| 9 | **Incorrect readiness/liveness probes causing frequent restarts** | Even if the pod is “Running”, a failing **readiness** probe can keep the service endpoint out of the load‑balancer, making the app appear unavailable. | ```bash\nkubectl describe pod s04-cpu-throttle -n kubexplain-eval | grep -A4 Probe\n```<br>Repeated “Readiness probe failed” events are a clue. | • Fix the probe command/URL or increase `initialDelaySeconds`/`periodSeconds`.<br>• Ensure the application is ready before the probe starts. |
|10| **Side‑car or init‑container still running** | A side‑car that does heavy background work (e.g., log aggregation) can consume CPU/memory, starving the main container. | ```bash\nkubectl get pod s04-cpu-throttle -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].name}:{.state.running}'\n```<br>Check which containers are still in `Running` state and their resource usage. | • Tune the side‑car’s resources or move it to a separate pod.<br>• Use `shareProcessNamespace` only if needed. |

---

## How to verify each cause (once you have cluster access)

1. **Collect metrics**  
   ```bash
   kubectl top pod s04-cpu-throttle -n kubexplain-eval
   kubectl top node <node-name>
   ```

2. **Inspect pod spec & events**  
   ```bash
   kubectl describe pod s04-cpu-throttle -n kubexplain-eval
   ```

3. **Check logs for application‑level clues**  
   ```bash
   kubectl logs s04-cpu-throttle -n kubexplain-eval --tail=200
   ```

4. **Run ad‑hoc diagnostics inside the container** (if the image includes the tools)  
   ```bash
   kubectl exec -n kubexplain-eval s04-cpu-throttle -- sh -c "df -h; free -m; cat /proc/$(pidof <app>)/status"
   ```

5. **Validate networking**  
   ```bash
   kubectl exec -n kubexplain-eval s04-cpu-throttle -- curl -s -o /dev/null -w "%{time_total}\\n" http://my‑service
   kubectl exec -n kubexplain-eval s04-cpu-throttle -- nslookup my‑service
   ```

6. **Look for node‑level pressure** (if you have metrics server or Prometheus)  
   - CPU > 80 % for a sustained period  
   - Memory > 90 %  
   - Disk I/O latency spikes  

---

## Quick “first‑pass” remediation checklist (when you can edit the manifest)

1. **Raise CPU & memory requests/limits** to realistic values (e.g., 2 CPU request, 4 CPU limit; 1 Gi memory request, 2 Gi limit).  
2. **Add a node selector or affinity** to move the pod to a less‑loaded node.  
3. **Enable vertical pod autoscaling** or **cluster autoscaler** to let the cluster add capacity automatically.  
4. **Tune the application** (GC, thread pools, retry back‑off) based on logs or profiling data.  
5. **Verify Service & DNS** – ensure endpoints are healthy and CoreDNS pods are running.  
6. **If storage is a bottleneck**, increase `ephemeral-storage` limits or attach a larger PVC.  

Apply changes via `kubectl apply -f <updated‑manifest.yaml>` and monitor the pod with `kubectl top` and logs until response times return to normal.  

---  

**Bottom line:** start with resource throttling (CPU → most common, then memory), then move outward to node pressure, networking, and finally application‑specific issues. Each verification step will quickly tell you which layer is the culprit, and the corresponding fix is listed beside it. Good luck!
