# s09 | mode=static | run=3 | 12.6s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why **`s09-web`** has an empty endpoint list for pod **`s09-bad-readiness`**  

| # | Likely cause (most to least probable) | How to verify (when you can reach the cluster) | How to fix |
|---|----------------------------------------|-----------------------------------------------|------------|
| 1 | **Service selector does not match the pod’s labels** | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o yaml\n``` <br>Look at `spec.selector`. Then list the pods that match those labels: <br>```bash\nkubectl get pods -n kubexplain‑eval -l <key>=<value>\n``` <br>If the pod does not appear, the selector is wrong or the pod’s labels are missing/typo‑ed. | Edit either the Service selector or the pod’s `metadata.labels` so they line‑up. Example: <br>```bash\nkubectl label pod s09‑bad‑readiness -n kubexplain‑eval app=web\n``` <br>or <br>```bash\nkubectl edit svc s09‑web -n kubexplain‑eval\n``` and adjust `spec.selector`. |
| 2 | **Readiness probe is failing, so the pod is not considered “Ready”** | ```bash\nkubectl describe pod s09‑bad‑readiness -n kubexplain‑eval\n``` <br>Check the `Readiness:` section under `Conditions`. If it shows `False` with a message like “Readiness probe failed”, the pod will be excluded from the Service endpoints. | Either fix the probe (correct path, port, initialDelaySeconds, etc.) **or** temporarily remove it to confirm the issue: <br>```bash\nkubectl edit pod s09‑bad‑readiness -n kubexplain‑eval\n``` and delete the `readinessProbe` block, then `kubectl delete pod` so it restarts. Once the pod becomes Ready, the endpoint will appear. |
| 3 | **`targetPort` in the Service does not match the container’s listening port** | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o yaml | grep targetPort\nkubectl describe pod s09‑bad‑readiness -n kubexplain‑eval | grep -i port\n``` <br>Confirm that the port the container actually listens on (e.g., `containerPort: 8080`) matches the Service’s `targetPort`. | Update the Service to use the correct `targetPort` or change the container to listen on the port the Service expects. <br>```bash\nkubectl edit svc s09‑web -n kubexplain‑eval\n``` |
| 4 | **Pod is not actually listening on the expected port** (application mis‑configuration) | Exec into the pod and test the port: <br>```bash\nkubectl exec -it s09‑bad‑readiness -n kubexplain‑eval -- sh\n# inside the pod\nnetstat -tlnp | grep <port>\n``` <br>or use `curl localhost:<port>` to see if the app responds. | Fix the application configuration (environment variable, command‑line flag, etc.) so it binds to the correct port, then redeploy/restart the pod. |
| 5 | **NetworkPolicy blocks traffic from the Service’s pod IP range** | ```bash\nkubectl get networkpolicy -n kubexplain‑eval\n``` <br>Inspect any policy that selects the pod (`podSelector`) and see its `ingress` rules. If there is no rule allowing traffic from the Service’s cluster IP range, the endpoint will be filtered out. | Either add an ingress rule that permits traffic from the Service’s namespace/IP block, or delete/modify the restrictive NetworkPolicy. |
| 6 | **Service type or port configuration error (e.g., wrong protocol, missing port)** | ```bash\nkubectl get svc s09‑web -n kubexplain‑eval -o yaml\n``` <br>Check that the `ports` list contains the correct `port`, `protocol` (TCP/UDP) and that the Service is of a type that creates endpoints (ClusterIP, NodePort, LoadBalancer). | Correct the Service definition (add missing port, fix protocol) and apply the updated manifest. |
| 7 | **EndpointSlice controller is not creating slices (cluster‑wide issue)** | ```bash\nkubectl get endpointslice -n kubexplain‑eval -l kubernetes.io/service-name=s09‑web\n``` <br>If no EndpointSlice objects exist, the controller may be unhealthy. Check controller manager logs or `kubectl get pods -n kube-system -l component=kube-controller-manager`. | Restart the controller manager (or the whole control‑plane if you are on a managed cluster) and verify that the EndpointSlice controller is running without errors. |
| 8 | **kube‑proxy on the node where the pod runs is malfunctioning** | ```bash\nkubectl get pods -n kube-system -l k8s-app=kube-proxy -o wide\n``` <br>Look for pods on the same node as `s09‑bad‑readiness` that are `CrashLoopBackOff` or `NotReady`. | Restart the kube‑proxy daemonset: <br>```bash\nkubectl rollout restart daemonset/kube-proxy -n kube-system\n``` and watch for the pod to become reachable again. |

---

### Quick “check‑list” you can run later

1. **Validate selector ↔ labels** – `kubectl get svc … -o yaml` → `spec.selector` vs `kubectl get pod … -o yaml` → `metadata.labels`.
2. **Readiness status** – `kubectl describe pod s09‑bad‑readiness`.
3. **Port alignment** – compare Service `targetPort` with pod `containerPort` and with what the app actually listens on.
4. **NetworkPolicy** – list policies in the namespace and verify ingress rules.
5. **EndpointSlices** – `kubectl get endpointslice -l kubernetes.io/service-name=s09‑web`.
6. **Control‑plane health** – ensure the controller manager and kube‑proxy are healthy.

Fix the first issue you discover; in most clusters the **selector mismatch** or a **failing readiness probe** accounts for > 80 % of empty‑endpoint problems. Once those are corrected, the pod should appear in the Service’s endpoint list and start receiving traffic.
