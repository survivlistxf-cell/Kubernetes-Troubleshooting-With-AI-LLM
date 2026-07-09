# s10 | mode=static | run=3 | 13.0s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **s10‑client** cannot reach **s10‑server** (same namespace)

| # | Probable cause | Why it’s common | How to verify (kubectl) | How to fix |
|---|----------------|----------------|--------------------------|------------|
| 1 | **Service selector does not match the server pod’s labels** | A mismatched `spec.selector` is the single biggest reason a Service has no endpoints, so traffic is dropped. | ```bash\nkubectl get svc s10-server -n kubexplain-eval -o jsonpath='{.spec.selector}'\nkubectl get pod -l <selector‑from‑svc> -n kubexplain-eval\n```If the second command returns **no pods**, the selector is wrong. | Edit the Service (or the pod) so the selector matches the pod’s labels, e.g. `kubectl edit svc s10-server -n kubexplain-eval` and set `spec.selector` to the labels that the server pod actually has. |
| 2 | **EndpointSlice is empty** | Even with a correct selector, the EndpointSlice controller may not have created endpoints (e.g., pod not ready, missing `targetPort`). | ```bash\nkubectl get endpointslices -l kubernetes.io/service-name=s10-server -n kubexplain-eval\n```If the `ENDPOINTS` column is `<none>` or the list is empty, the Service has no back‑ends. | Ensure the server pod is **Ready** (see #3) and that the Service’s `targetPort` matches a container port that is **exposed**. |
| 3 | **Server pod not Ready (readiness probe failing)** | Pods that fail their readiness probe are removed from the Service’s endpoint list, so they receive no traffic. | ```bash\nkubectl describe pod <s10-server-pod> -n kubexplain-eval | grep -i readiness\n```Look for `Readiness probe failed` events. | Fix the readiness probe (correct path/port, adjust `initialDelaySeconds`, etc.) or temporarily disable it while debugging. |
| 4 | **Port mismatch – Service port vs. targetPort** | If `spec.ports[].targetPort` points to a port that the server container does **not** listen on, kube‑proxy cannot forward traffic. | ```bash\nkubectl get svc s10-server -n kubexplain-eval -o yaml | grep targetPort\nkubectl exec -it <s10-server-pod> -n kubexplain-eval -- netstat -tlnp | grep <targetPort>\n```If the port is not listening, the Service cannot reach the pod. | Change the Service’s `targetPort` to the actual container port, or modify the container to listen on the expected port. |
| 5 | **NetworkPolicy blocks traffic** | A `NetworkPolicy` that selects the server pod but does **not** allow traffic from the client pod will silently drop packets. | ```bash\nkubectl get networkpolicy -n kubexplain-eval\nkubectl describe networkpolicy <policy-name> -n kubexplain-eval\n```Check the `podSelector` and `ingress` rules. | Add an ingress rule that allows traffic from the client pod (or its namespace) or delete the restrictive policy. |
| 6 | **DNS resolution failure** | Even in the same namespace, the client may be using the Service name and DNS could be broken, causing the request to time out. | ```bash\nkubectl exec -it <s10-client-pod> -n kubexplain-eval -- nslookup s10-server\n```If the lookup fails or returns `SERVFAIL`, DNS is the issue. | Verify CoreDNS pods are healthy (`kubectl get pods -n kube-system -l k8s-app=kube-dns`) and that the Service’s `clusterIP` is reachable. Restart CoreDNS or fix its ConfigMap if needed. |
| 7 | **Hair‑pin (self‑IP) problem** | If the client pod is scheduled on the same node as the server pod and the CNI does not support hair‑pin traffic, the client cannot reach the Service IP (even though direct pod‑to‑pod works). | ```bash\nkubectl get pod -o wide -n kubexplain-eval | grep s10-client\nkubectl get pod -o wide -n kubexplain-eval | grep s10-server\n```If they share the same node, test direct pod IP connectivity (`curl http://<server-pod-ip>:<port>`). | Enable hair‑pin mode on the kubelet (`--hairpin-mode=hairpin-veth` or `promiscuous-bridge`) or move the client to a different node. |
| 8 | **kube‑proxy malfunction (iptables/IPVS rules missing)** | Corrupted iptables/IPVS rules on the node can prevent Service IP translation. | ```bash\nkubectl get nodes -o name\nkubectl exec -it <node> -- iptables -L -n | grep <service‑clusterIP>\n```If the rules are absent, kube‑proxy is not programming them. | Restart the `kube-proxy` daemonset (`kubectl rollout restart ds/kube-proxy -n kube-system`) or check its logs for errors. |
| 9 | **Pod security policies / PSP / Seccomp blocking outbound traffic** | A restrictive PSP or Seccomp profile may prevent the client from opening outbound connections. | ```bash\nkubectl describe pod s10-client -n kubexplain-eval | grep -i securityContext\n```Look for `allowPrivilegeEscalation: false` or `seccompProfile` that blocks network syscalls. | Adjust the PSP/Seccomp profile to permit `connect` syscalls, or run the client with a less‑restricted security context. |
| 10 | **Node‑level firewall / cloud‑provider security group** | External firewalls (e.g., AWS security groups, GCP firewall rules) sometimes block the pod‑to‑pod traffic on the Service port. | ```bash\nkubectl get nodes -o wide\n# then inspect the cloud provider’s firewall rules for the node’s subnet\n```If the port is not allowed, traffic is dropped. | Open the required port in the cloud firewall or disable host‑level firewalls for the node network. |

---

### Quick “checklist” you can run later (once you have cluster access)

1. **Service definition** – verify `selector` and `targetPort`.
2. **Endpoints** – `kubectl get endpointslices -l kubernetes.io/service-name=s10-server`.
3. **Pod readiness** – `kubectl get pod s10-server -o wide` and `kubectl describe pod s10-server`.
4. **Port listening** – `kubectl exec s10-server -- netstat -tlnp`.
5. **NetworkPolicy** – list policies and inspect ingress rules.
6. **DNS** – `kubectl exec s10-client -- nslookup s10-server`.
7. **Hair‑pin** – check node placement and try direct pod‑IP curl.
8. **kube‑proxy** – look at kube‑proxy logs and iptables/IPVS rules on the node.
9. **Security contexts** – review PSP/Seccomp for the client pod.
10. **Node firewall** – confirm cloud‑provider security groups allow the Service port.

Address the items in the order above; the first three (selector mismatch, empty endpoints, readiness probe) resolve the overwhelming majority of intra‑namespace Service‑reachability problems. Once those are confirmed healthy, move down the list to network‑policy, DNS, hair‑pin, and kube‑proxy issues.
