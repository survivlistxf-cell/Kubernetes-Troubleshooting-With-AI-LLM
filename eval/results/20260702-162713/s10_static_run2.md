# s10 | mode=static | run=2 | 16.5s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **s10‑client** can’t reach **s10‑server** (same namespace)

| # | Probable cause (most → least common) | How to verify (when you have cluster access) | How to fix |
|---|--------------------------------------|---------------------------------------------|------------|
| 1 | **Service selector does not match the server pod’s labels** – the Service has **no Endpoints** (or the EndpointSlice is empty). | ```bash\nkubectl get svc s10‑server -n kubexplain‑eval -o wide\nkubectl describe svc s10‑server -n kubexplain‑eval\nkubectl get endpointslices -l kubernetes.io/service-name=s10‑server -n kubexplain‑eval\n```<br>If the `ENDPOINTS` column is empty or the EndpointSlice shows `<none>`, the selector is wrong. | Edit the Service (or the Deployment/Pod) so that the selector matches a label that really exists on the server pod, e.g. `kubectl edit svc s10‑server …` or add the missing label to the server pod (`kubectl label pod <pod> app=s10-server`). |
| 2 | **Port mismatch** – the Service’s `port`/`targetPort` does not correspond to the port the server container is listening on. | ```bash\nkubectl get svc s10‑server -n kubexplain‑eval -o jsonpath='{.spec.ports[*].{port:port,targetPort:targetPort}}'\nkubectl describe pod <s10‑server‑pod> -n kubexplain‑eval | grep -i ports\n```<br>Check that the `targetPort` is the numeric port (or a named port that exists in the pod spec). | Change the Service to use the correct `targetPort` (or rename the container port). If the server is listening on a different port, update the container spec and redeploy. |
| 3 | **NetworkPolicy blocks traffic** – a Namespace‑wide or pod‑specific NetworkPolicy denies egress from the client or ingress to the server. | ```bash\nkubectl get networkpolicy -n kubexplain‑eval\nkubectl describe networkpolicy <policy‑name> -n kubexplain‑eval\n```<br>Look for policies that select `s10‑client` (egress) or `s10‑server` (ingress) and that **do not** allow traffic on the Service port. | Either add an allow rule to the relevant NetworkPolicy (e.g. `podSelector: {app: s10‑server}` with `ports: [{port: <svc‑port>}]`) or delete/modify the restrictive policy. |
| 4 | **Server pod not actually listening** – the container is running but the application is not bound to the expected port or is crashing. | ```bash\nkubectl logs <s10‑server‑pod> -n kubexplain‑eval\nkubectl exec -it <s10‑server‑pod> -n kubexplain‑eval -- netstat -tlnp | grep <targetPort>\n```<br>If nothing is listening, the app is mis‑configured. | Fix the application configuration (e.g. change the config file or environment variable that sets the listening port) and restart the pod. |
| 5 | **DNS resolution failure** – the client cannot resolve `s10‑server` to the Service IP. | ```bash\nkubectl exec -it <s10‑client‑pod> -n kubexplain‑eval -- nslookup s10‑server\nkubectl exec -it <s10‑client‑pod> -n kubexplain‑eval -- cat /etc/resolv.conf\n```<br>If `nslookup` returns *NXDOMAIN* or times out, DNS is broken. | Verify that the `kube-dns`/`coredns` deployment is healthy (`kubectl get pods -n kube-system -l k8s-app=kube-dns`). Restart it if needed, or check the `ConfigMap` for correct `clusterDomain`. |
| 6 | **Hair‑pin / kube‑proxy issue** – a pod trying to reach its own Service IP is blocked because the node’s kube‑proxy is not allowing “hairpin” traffic (common with iptables mode on some CNI plugins). | ```bash\nkubectl exec -it <s10‑client‑pod> -n kubexplain‑eval -- curl -s http://s10‑server.<namespace>.svc.cluster.local:<svc‑port>\n```<br>If the request works from another pod but not from the server pod itself, hair‑pin is the suspect. | Ensure the kubelet is started with `--hairpin-mode=hairpin-veth` (or `promiscuous-bridge`). On many managed clusters this is already set; otherwise edit the kubelet config and restart the node. |
| 7 | **CNI/network plugin problem** – the underlying pod network is fragmented (e.g., missing routes, MTU mismatch) so pods cannot reach each other. | ```bash\nkubectl exec -it <s10‑client‑pod> -n kubexplain‑eval -- ip route\nkubectl exec -it <s10‑client‑pod> -n kubexplain‑eval -- ping -c 3 <s10‑server‑pod‑IP>\n```<br>If the pod IP is reachable but the Service IP is not, the issue is likely in kube‑proxy; if the pod IP itself is unreachable, the CNI is at fault. | Check the CNI daemonset logs (`kubectl -n kube-system logs <cni‑pod>`). Restart the CNI pods or reinstall the CNI plugin. Verify that the node’s `kube-proxy` is running and has the correct mode (iptables or IPVS). |
| 8 | **Service type mismatch or mis‑named Service** – the Service is defined as `ExternalName` or the client is using the wrong DNS name (e.g., missing namespace). | ```bash\nkubectl get svc s10‑server -n kubexplain‑eval -o yaml | grep type\n```<br>If `type: ExternalName` or the client uses `s10‑server` without the namespace qualifier and there is another Service with that name in a different namespace, DNS may resolve to the wrong IP. | Change the Service to `type: ClusterIP` (or the intended type) and make sure the client uses the fully‑qualified name `s10‑server.kubexplain‑eval.svc.cluster.local`. |
| 9 | **PodSecurityPolicy / Seccomp / AppArmor restrictions** – the client pod is prevented from making outbound TCP connections. | ```bash\nkubectl describe pod s10‑client -n kubexplain‑eval | grep -i securityContext\n```<br>Look for `allowPrivilegeEscalation: false` with a restrictive `seccompProfile`. | Adjust the pod’s `securityContext` or the PSP/PodSecurityAdmission policy to permit outbound network traffic on the required port. |

---

### How to use the checklist when you regain access

1. **Start with the Service definition** – `kubectl describe svc s10‑server`.  
   *If there are no endpoints, fix the selector first (Cause 1).*

2. **Confirm the server pod is listening** – `kubectl exec … netstat` or `ss`.  
   *If the port isn’t open, fix the application or targetPort (Cause 2‑4).*

3. **Test DNS from the client** – `nslookup s10‑server` or `dig`.  
   *If DNS fails, investigate CoreDNS (Cause 5).*

4. **Check NetworkPolicies** – list them and verify they allow traffic on the Service port (Cause 3).

5. **Try a direct pod‑to‑pod connection** – `curl http://<server‑pod‑IP>:<port>` from the client.  
   *If this works but the Service IP still fails, look at kube‑proxy / hair‑pin (Cause 6).*

6. **If even pod‑to‑pod fails**, suspect the CNI (Cause 7). Look at node routes, CNI pod logs, and kube‑proxy status.

7. **Finally, verify Service type and naming** (Cause 8) and any security policies that could block egress (Cause 9).

Following this ordered list will usually pinpoint the root cause quickly, because the most common failure is a selector/endpoint mismatch, followed by port mismatches and NetworkPolicy blocks. Once the specific cause is identified, apply the corresponding fix and re‑test the client‑to‑server request.
