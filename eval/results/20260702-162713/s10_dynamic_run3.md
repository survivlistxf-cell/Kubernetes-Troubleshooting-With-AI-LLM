# s10 | mode=dynamic | run=3 | 12.7s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **s10‑client** can’t reach **s10‑server** (same namespace)

| # | Likely cause | Why it’s common | How to verify (when you get back into the cluster) | How to fix |
|---|--------------|----------------|---------------------------------------------------|------------|
| 1 | **Service does not exist or is named incorrectly** | The first thing to check – a missing Service will always give a timeout. | ```bash\nkubectl get svc s10-server -n kubexplain-eval\n```<br>If the command returns “Error from server (NotFound) …” the Service is absent or the name is wrong. | Create or expose the server pod:<br>```bash\nkubectl expose pod s10-server --name=s10-server --port=80 --target-port=8080 -n kubexplain-eval\n```<br>or apply the correct Service manifest. |
| 2 | **Service selector does not match the server pod’s labels** | A Service that exists but selects no pods will have empty EndpointSlices, leading to time‑outs. | ```bash\nkubectl describe svc s10-server -n kubexplain-eval\nkubectl get pods -l <selector‑from‑svc> -n kubexplain-eval\n```<br>If the second command returns no pods, the selector is wrong. | Edit the Service (or the pod) so the selector matches the pod’s `metadata.labels`. Example:<br>```bash\nkubectl edit svc s10-server -n kubexplain-eval   # adjust .spec.selector\n# or\nkubectl label pod s10-server app=s10-server -n kubexplain-eval\n``` |
| 3 | **EndpointSlices are empty (pod not ready or failing readiness probe)** | Even with a correct selector, a pod that is not *Ready* is removed from the endpoints list. | ```bash\nkubectl get endpointslices -l kubernetes.io/service-name=s10-server -n kubexplain-eval\nkubectl describe pod s10-server -n kubexplain-eval\n```<br>Look for `Ready: false` or readiness‑probe failures. | Fix the pod’s readiness probe, resource limits, or any startup error so the pod becomes Ready. Then the endpoint will appear automatically. |
| 4 | **Port mismatch between Service and pod** | The Service may be exposing port 80 while the container actually listens on a different port (or the `targetPort` is wrong). | ```bash\nkubectl get svc s10-server -o yaml -n kubexplain-eval\nkubectl describe pod s10-server -n kubexplain-eval | grep -i port\n```<br>Confirm that `spec.ports[].port` → Service port and `spec.ports[].targetPort` → container port. | Adjust the Service `targetPort` (or the container’s `containerPort`) so they line up. |
| 5 | **DNS resolution failure** | Clients normally reach a Service by DNS name; if DNS is broken the request never leaves the pod. | ```bash\nkubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server\n```<br>Expect an IP address; “server can’t find” means DNS is the problem. | Verify that CoreDNS pods are healthy (`kubectl get pods -n kube-system -l k8s-app=kube-dns`). Restart or fix CoreDNS if needed, or use the fully‑qualified name `s10-server.kubexplain-eval.svc.cluster.local`. |
| 6 | **NetworkPolicy blocks traffic** | A default‑deny NetworkPolicy can silently drop traffic between pods in the same namespace. | ```bash\nkubectl get networkpolicy -n kubexplain-eval\n```<br>If a policy exists, inspect its `ingress` rules to see whether traffic from `s10-client` is allowed. | Add an ingress rule that permits traffic from the client pod (or label selector) to the server pod, or delete the restrictive policy. |
| 7 | **Hair‑pin / self‑IP issue** (client and server on the same node and pod tries to reach its own Service IP) | In iptables mode, a pod may be unable to reach its own Service IP unless hairpin mode is enabled. | ```bash\nkubectl describe node <node‑name> | grep hairpin\n```<br>Look for `--hairpin-mode=hairpin-veth` (or `promiscuous-bridge`). | Restart kubelet with `--hairpin-mode=hairpin-veth` (or set the flag in the node config). |
| 8 | **kube‑proxy malfunction (iptables/ipvs rules missing)** | If kube‑proxy isn’t programming the Service IP on the node, traffic never reaches the endpoints. | ```bash\nkubectl logs -n kube-system -l k8s-app=kube-proxy --tail=20\n```<br>Check for errors about endpoint updates. | Restart the kube‑proxy DaemonSet, or switch the mode (iptables ↔ ipvs) and verify the rules with `iptables -L -t nat` or `ipvsadm -Ln`. |
| 9 | **Cluster‑wide network outage / node not Ready** | If the node hosting the server pod is NotReady, the pod’s IP may be unreachable. | ```bash\nkubectl get nodes\n```<br>Look for `STATUS` ≠ `Ready`. | Fix the underlying node issue (e.g., kubelet crash, network partition) or evict the pod to a healthy node. |

---

### How to use the checklist when you regain access

1. **Start with the Service itself** – `kubectl get svc …` → confirm existence and name.  
2. **Check selector → pods** – make sure the Service actually selects the server pod.  
3. **Verify endpoints** – `kubectl get endpointslices …` should list the server pod IP(s).  
4. **Confirm pod readiness** – `kubectl describe pod …` for readiness‑probe status.  
5. **Test DNS** – `nslookup s10-server` from the client pod.  
6. **Look for NetworkPolicy** – `kubectl get networkpolicy …`.  
7. **If everything above is fine, move outward** – kube‑proxy logs, node health, CoreDNS health, hairpin mode.

Following this order usually uncovers the problem within a few minutes, because the first three items (missing Service, selector mismatch, empty endpoints) account for the overwhelming majority of “pod cannot reach service” time‑outs.
