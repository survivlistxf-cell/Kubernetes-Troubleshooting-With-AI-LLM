# s09 | mode=none | run=2 | 12.8s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why the pod `s09-bad-readiness` has no endpoints in service `s09-web`

| # | Possible cause | Why it is common | How to verify (when you have access) | How to fix |
|---|----------------|------------------|--------------------------------------|------------|
| 1 | **Readiness probe is failing** – the pod is marked *NotReady* so the Service excludes it from its Endpoints list. | A mis‑configured HTTP/TCP/exec probe (wrong path, port, timeout, initialDelay, etc.) is the single most frequent reason for empty endpoints. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'\nkubectl describe pod s09-bad-readiness -n kubexplain-eval | grep -i readiness\n```<br>Check the `Ready` condition (`True` = ready, `False` = not ready) and the events that show “Readiness probe failed”. | *Inspect* the probe definition in the pod spec (`kubectl edit pod …` or the Deployment/StatefulSet).<br>*Adjust* the probe parameters (path, port, `initialDelaySeconds`, `periodSeconds`, `failureThreshold`, etc.) so that the container returns a successful check.<br>*Apply* the corrected manifest and let the pod restart, then verify that the pod becomes Ready and appears in the Service’s endpoints. |
| 2 | **Pod labels do not match the Service selector** – the Service cannot find any pods that satisfy its label query. | A typo or stale label change (e.g., after a rollout) will leave the Service with an empty endpoint list even though the pod is Running. | ```bash\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.selector}'\nkubectl get pod s09-bad-readiness -n kubexplain-eval --show-labels\n```<br>Compare the selector map with the pod’s actual labels. | *Edit* the pod (or its controller) to add the missing label(s) that the Service expects.<br>*Or* modify the Service’s selector to match the existing pod labels.<br>After the change, the endpoints list should populate automatically. |
| 3 | **Pod is in a different namespace than the Service** – Services are namespace‑scoped, so a Service cannot see pods outside its own namespace. | Occasionally a pod is created in the wrong namespace (e.g., a typo in the manifest) while the Service lives elsewhere. | ```bash\nkubectl get pod s09-bad-readiness -o jsonpath='{.metadata.namespace}'\nkubectl get svc s09-web -o jsonpath='{.metadata.namespace}'\n``` | If the namespaces differ, recreate the pod (or its controller) in the same namespace as the Service, or create a Service in the pod’s namespace and use an `ExternalName`/Ingress to expose it. |
| 4 | **Pod is part of a different controller (e.g., a ReplicaSet) that has a different pod template** – the pod you see is a leftover from a previous rollout that does not carry the correct labels or probes. | During a rolling update, old pods may stay Running while the new Deployment’s selector has changed. | ```bash\nkubectl get rs -n kubexplain-eval -o wide | grep s09-bad-readiness\nkubectl describe pod s09-bad-readiness -n kubexplain-eval | grep OwnerReference\n``` | *Delete* the stale pod (`kubectl delete pod …`) and let the current controller recreate it with the right spec.<br>*Or* update the controller’s selector/template to be consistent with the Service. |
| 5 | **NetworkPolicy blocks traffic to the pod’s readiness port** – the probe succeeds locally but the Service’s kube-proxy cannot reach the pod, so it is omitted from endpoints. | When a NetworkPolicy restricts ingress to a specific set of pods/ports, the kube-proxy health‑check traffic may be denied, causing the endpoint controller to drop the pod. | ```bash\nkubectl get networkpolicy -n kubexplain-eval\nkubectl describe networkpolicy <policy-name> -n kubexplain-eval\n``` | *Adjust* the NetworkPolicy to allow traffic from the kube‑proxy (typically from the node’s IP range) to the pod’s readiness port, or add an explicit `allow-from: []` rule for the Service’s selector. |
| 6 | **Pod is using a non‑standard port for the Service** – the Service’s `port`/`targetPort` does not correspond to any container port, so kube-proxy cannot route traffic. | If the Service points to a port that the container never opens, the endpoint is still created but traffic will be dropped; however some clusters also prune endpoints when the targetPort is invalid. | ```bash\nkubectl get svc s09-web -n kubexplain-eval -o yaml | grep targetPort\nkubectl describe pod s09-bad-readiness -n kubexplain-eval | grep -i ports\n``` | *Update* the Service’s `targetPort` to match the container’s actual listening port, or modify the container to expose the expected port. |
| 7 | **Pod is in a `Terminating` state but still shows `Running`** – the pod is being deleted (e.g., due to a failed graceful shutdown) and is excluded from endpoints. | A pod stuck in termination can appear Running while the endpoint controller removes it. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.deletionTimestamp}'\n``` | *Force delete* the pod (`kubectl delete pod … --grace-period=0 --force`) and let the controller recreate it, or fix the underlying issue that prevents graceful termination. |

---

### How to verify the root cause when you regain access

1. **Check the pod’s Ready condition** – `kubectl get pod … -o wide` shows `READY` as `0/1` if the readiness probe is failing.  
2. **Inspect the Service’s endpoints** – `kubectl get endpoints s09-web -n kubexplain-eval -o yaml` will list the IPs; an empty list confirms the problem.  
3. **Compare Service selector vs. pod labels** – mismatched keys/values are a quick giveaway.  
4. **Review the pod spec for readinessProbe** – `kubectl describe pod …` or `kubectl get pod … -o yaml`.  
5. **Look for events** – `kubectl describe pod …` and `kubectl describe svc …` surface warnings like “Readiness probe failed” or “No endpoints”.  
6. **Check controller ownership** – ensure the pod is managed by the expected Deployment/ReplicaSet.  
7. **Examine NetworkPolicies** – any policy that selects the pod’s namespace and denies ingress on the probe port will block endpoint registration.  

---

### General remediation steps (apply in order of the ranked causes)

1. **Fix the readiness probe** – correct path/port, increase timeout, or temporarily remove the probe to confirm the Service works.  
2. **Align labels** – add missing label(s) to the pod (or its controller) or adjust the Service selector.  
3. **Ensure namespace consistency** – recreate resources in the same namespace.  
4. **Clean up stale pods** – delete pods that belong to an old controller version.  
5. **Update NetworkPolicy** – allow kube-proxy health‑check traffic.  
6. **Correct Service targetPort** – match the container’s listening port.  
7. **Resolve termination issues** – delete stuck pods and investigate finalizers or graceful‑shutdown scripts.

By walking through these checks in the order shown, you’ll quickly pinpoint why `s09-bad-readiness` is not appearing as an endpoint for `s09-web` and be able to restore traffic flow.
