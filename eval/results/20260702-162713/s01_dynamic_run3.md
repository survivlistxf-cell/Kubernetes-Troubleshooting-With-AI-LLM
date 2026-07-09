# s01 | mode=dynamic | run=3 | 14.2s | needs_search=0

**Q:** My pod 's01-crashloop' in namespace 'kubexplain-eval' keeps restarting. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** app exits at startup (exit code 1 / bad command); fix command or image

---

## Most likely reasons a pod ends up in **CrashLoopBackOff** (ordered by probability)

| # | Reason | Why it’s common | How to verify (kubectl) | Typical fix |
|---|--------|----------------|--------------------------|-------------|
| 1 | **Application error / non‑zero exit code** | The container starts, runs the entry‑point, exits with an error, so the kubelet restarts it repeatedly. | ```bash\nkubectl logs s01-crashloop -n kubexplain-eval --previous\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"State:\" -A3\n``` | Look at the log output and the `Reason:` field (e.g. `Error`, `ExitCode`). Fix the code, command line, or configuration that makes the process exit. Re‑apply the corrected image or manifest. |
| 2 | **Liveness probe failure** | A healthy container can be killed by the kubelet if its liveness probe keeps failing, causing a restart loop. | ```bash\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"Liveness\" -A5\n``` | Check the probe definition (httpGet, exec, tcpSocket) and the failure threshold. Make the probe less strict, increase `initialDelaySeconds`, or fix the service the probe checks. |
| 3 | **Readiness probe failure combined with a restart policy that recreates the pod** (e.g., part of a Deployment) | If a pod never becomes Ready, the controller may delete and recreate it, appearing as a crash loop. | ```bash\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"Readiness\" -A5\n``` | Adjust the readiness probe or the underlying service so the pod can become Ready. |
| 4 | **Out‑of‑Memory (OOMKilled)** | The container exceeds its memory limit; the kernel kills it, and the pod restarts. | ```bash\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"OOMKilled\" -A3\n``` | Increase the memory `requests`/`limits` or reduce the app’s memory usage. |
| 5 | **Image pull / registry authentication failure** (often shows as `ImagePullBackOff`, but can cascade to CrashLoopBackOff if the pod retries) | Wrong image name, missing tag, or lack of credentials prevents the container from starting. | ```bash\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"Pull\" -A5\n``` | Verify the image name, tag, and that the image exists in the registry. Add a proper `imagePullSecret` if needed, then delete the pod so it pulls again. |
| 6 | **Missing ConfigMap / Secret** | The container crashes because required configuration files or env vars are not present. | ```bash\nkubectl describe pod s01-crashloop -n kubexplain-eval | grep -i \"ConfigMap\\|Secret\" -A3\n``` | Ensure the referenced ConfigMap/Secret exists and is correctly mounted or injected. Create or update it, then restart the pod. |
| 7 | **Incorrect command / args** (e.g., typo in `command` field) | The pod runs a non‑existent binary or wrong arguments, exiting immediately. | ```bash\nkubectl get pod s01-crashloop -n kubexplain-eval -o yaml | grep -i \"command\\|args\" -A3\n``` | Fix the `command`/`args` in the pod spec (or Deployment) and re‑apply. |
| 8 | **Dependency not ready** (e.g., DB, external service) | The app exits because it cannot reach a required service at start‑up. | ```bash\nkubectl logs s01-crashloop -n kubexplain-eval --previous | grep -i \"connection\" -A5\n``` | Ensure the dependent service is reachable (network policies, Service name, DNS). Add retry logic or increase `initialDelaySeconds` of the liveness probe. |
| 9 | **Node‑level issues** (disk pressure, network partition) | The kubelet may kill pods on a node that is under pressure, causing restarts. | ```bash\nkubectl describe node <node‑name> | grep -i \"Pressure\" -A5\n``` | Resolve node resource pressure (e.g., free disk, adjust eviction thresholds) or move the pod to a healthy node. |
|10| **Finalizer / admission webhook blocking pod creation** (rare) | A webhook rejects the pod after it starts, leading to termination and restart. | ```bash\nkubectl get pod s01-crashloop -n kubexplain-eval -o yaml | grep -i \"finalizers\" -A2\n``` | Disable or fix the offending webhook / finalizer, then delete and recreate the pod. |

---

## How to systematically verify the root cause

1. **Inspect the pod’s status and recent events**  
   ```bash
   kubectl describe pod s01-crashloop -n kubexplain-eval
   ```
   *Look for* `State:`, `Reason:`, `Message:` fields under each container, and the `Events` section at the bottom. Typical reasons appear as `CrashLoopBackOff`, `OOMKilled`, `Error`, `ImagePullBackOff`, etc.

2. **Check the container’s last logs** (the `--previous` flag shows logs from the container instance that just terminated)  
   ```bash
   kubectl logs s01-crashloop -n kubexplain-eval --previous
   ```

3. **If the logs are empty or the pod never starts**, examine the pod spec for probes, resources, command/args, and volume mounts:
   ```bash
   kubectl get pod s01-crashloop -n kubexplain-eval -o yaml
   ```

4. **Validate related objects** (ConfigMaps, Secrets, Services, PVCs) that the pod references:
   ```bash
   kubectl get configmap -n kubexplain-eval
   kubectl get secret -n kubexplain-eval
   kubectl get svc -n kubexplain-eval
   ```

5. **If you suspect node pressure**, find the node the pod is scheduled on and inspect it:
   ```bash
   NODE=$(kubectl get pod s01-crashloop -n kubexplain-eval -o jsonpath='{.spec.nodeName}')
   kubectl describe node $NODE | grep -i "Pressure\|Disk\|Memory"
   ```

---

## Quick “fix‑first” checklist

1. **Pull the latest logs** – if they show a clear application error, fix the code or environment variables and redeploy.
2. **Check liveness/readiness probes** – if they are too aggressive, increase `initialDelaySeconds`, `periodSeconds`, or adjust the probe command/endpoint.
3. **Review memory limits** – bump the `resources.limits.memory` (and optionally `requests.memory`) if OOMKilled appears.
4. **Validate image name and pull secret** – correct any typo, ensure the image exists, and that the secret is attached.
5. **Confirm ConfigMaps/Secrets** – create or update missing objects, then delete the pod so it restarts with the new data.
6. **Correct command/args** – edit the manifest (`kubectl edit deployment …` or the pod yaml) to use the right entrypoint.
7. **Ensure external dependencies are reachable** – test DNS (`kubectl exec … -- nslookup <service>`) and network policies.
8. **If node pressure is the culprit**, either free up resources on that node or cordon & drain it, letting the scheduler place the pod elsewhere:
   ```bash
   kubectl cordon $NODE
   kubectl drain $NODE --ignore-daemonsets --delete-emptydir-data
   ```

After applying the appropriate fix, delete the problematic pod so the controller creates a fresh instance:

```bash
kubectl delete pod s01-crashloop -n kubexplain-eval
```

The new pod should start without entering `CrashLoopBackOff` if the underlying issue has been resolved.
