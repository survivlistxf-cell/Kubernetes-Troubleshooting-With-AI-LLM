# s10 | mode=none | run=1 | 11.5s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons a pod cannot reach a service in the same namespace  

| # | Likely cause | Why it matters | How to verify | How to fix |
|---|--------------|----------------|---------------|-----------|
| 1 | **Service has no endpoints** (the selector does not match the server pod) | If the Service’s `spec.selector` does not select the server pod, the Service IP has no backing pods, so traffic times‑out. | ```bash\nkubectl get svc s10-server -n kubexplain-eval -o wide\nkubectl get endpoints s10-server -n kubexplain-eval\n```<br>Check that the endpoint list contains the IP of the `s10-server` pod. | Adjust the Service selector or add appropriate labels to the server pod so they match. Example: ```yaml\nspec:\n  selector:\n    app: s10-server\n``` then label the pod: `kubectl label pod <pod> app=s10-server -n kubexplain-eval`. |
| 2 | **NetworkPolicy blocks traffic** | A `NetworkPolicy` that selects the client pod (or the server pod) may deny egress/ingress to the Service IP or pod IPs. | ```bash\nkubectl get networkpolicy -n kubexplain-eval\nkubectl describe networkpolicy <policy-name> -n kubexplain-eval\n```<br>Look for policies that select `s10-client` (or `s10-server`) and that have no `allow` rule for the other pod’s namespace/labels. | Either add an explicit allow rule for traffic between the two pods, or delete/modify the restrictive policy. Example allow rule: ```yaml\nspec:\n  podSelector:\n    matchLabels:\n      app: s10-server\n  ingress:\n  - from:\n    - podSelector:\n        matchLabels:\n          app: s10-client\n``` |
| 3 | **Cluster DNS failure** (client resolves Service name to wrong IP) | Pods normally reach a Service via its DNS name (`s10-server`). If CoreDNS is down or the pod’s `/etc/resolv.conf` is mis‑configured, the name may resolve to an empty address or the wrong IP, causing a timeout. | ```bash\nkubectl exec -n kubexplain-eval s10-client -- nslookup s10-server\nkubectl get pods -n kube-system -l k8s-app=kube-dns\nkubectl logs -n kube-system -l k8s-app=kube-dns\n``` | Restart CoreDNS deployment, or fix the pod’s DNS config. Example: ```bash\nkubectl rollout restart deployment coredns -n kube-system\n``` If the pod uses a custom `dnsPolicy` (e.g., `Default`), change it to `ClusterFirst`. |
| 4 | **Pod IP conflict / IPAM issue** | Rare, but if the CNI allocated overlapping IPs or the node’s network interface is mis‑configured, packets never reach the destination. | ```bash\nkubectl get pod s10-client -n kubexplain-eval -o jsonpath='{.status.podIP}'\nkubectl get pod s10-server -n kubexplain-eval -o jsonpath='{.status.podIP}'\nping -c 3 <server-pod-ip>   # from inside the client pod\n``` | Check the CNI logs on the node, or restart the CNI daemonset. Re‑create the pods so they get fresh IPs. |
| 5 | **Service type mismatch / port mis‑configuration** | If the Service’s `port`/`targetPort` do not match the container port the server is listening on, the connection will be accepted by the Service IP but immediately dropped. | ```bash\nkubectl describe svc s10-server -n kubexplain-eval\nkubectl exec -n kubexplain-eval s10-server -- netstat -tlnp | grep <expected-port>\n``` | Align the Service `port` and `targetPort` with the container’s listening port, or update the server container to listen on the expected port. |
| 6 | **Pod security policies / PSP / seccomp** | A restrictive PSP or seccomp profile could block outbound connections from the client pod. | ```bash\nkubectl get pod s10-client -n kubexplain-eval -o yaml | grep -i securityContext -A5\n``` | Relax the security context (allow `NET_RAW` capability, remove `seccompProfile` that blocks `connect`), or use a less restrictive PSP/PodSecurityAdmission level. |
| 7 | **Node‑level firewall / hostNetwork conflict** | If the pods are running with `hostNetwork: true` or the node’s firewall blocks the pod‑to‑pod CIDR, traffic will be dropped. | ```bash\nkubectl get pod s10-client -n kubexplain-eval -o yaml | grep hostNetwork\n# on the node (if you can ssh) check iptables rules for the pod CIDR\n``` | Remove `hostNetwork: true` if not needed, or adjust the node firewall to allow traffic on the pod CIDR. |

---

### General verification workflow you can run when you regain access

1. **Confirm the Service resolves correctly**  
   ```bash
   kubectl exec -n kubexplain-eval s10-client -- nslookup s10-server
   ```
   Expected: an A record pointing to the Service ClusterIP.

2. **Check that the Service has endpoints**  
   ```bash
   kubectl get endpoints s10-server -n kubexplain-eval
   ```
   Expected: at least one IP that matches the `s10-server` pod.

3. **Test connectivity directly to the pod IP** (bypassing Service)  
   ```bash
   POD_IP=$(kubectl get pod s10-server -n kubexplain-eval -o jsonpath='{.status.podIP}')
   kubectl exec -n kubexplain-eval s10-client -- curl -m 5 http://$POD_IP:<port>/healthz
   ```
   If this works but the Service name does not, the problem is DNS or Service definition.

4. **Inspect NetworkPolicies**  
   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   kubectl describe networkpolicy <policy> -n kubexplain-eval
   ```

5. **Validate CoreDNS health**  
   ```bash
   kubectl get pods -n kube-system -l k8s-app=kube-dns
   kubectl logs -n kube-system -l k8s-app=kube-dns
   ```

6. **Review pod security / PSP**  
   ```bash
   kubectl get pod s10-client -n kubexplain-eval -o yaml | grep -i securityContext -A10
   ```

7. **Look at CNI / node networking** (if you have node access)  
   ```bash
   # Example for Calico
   kubectl -n kube-system logs daemonset/calico-node
   ```

---

### Quick “first‑fix” checklist

1. **Endpoints missing?** → Fix Service selector or pod labels.  
2. **NetworkPolicy blocking?** → Add an allow rule for `app=s10-client` → `app=s10-server`.  
3. **DNS not resolving?** → Restart CoreDNS, verify `dnsPolicy: ClusterFirst`.  
4. **Port mismatch?** → Align Service `targetPort` with container port.  
5. **CNI/IP conflict?** → Restart CNI daemonset, delete/re‑create pods.  

Address the items in the order above; in most clusters the **missing endpoints** or a **restrictive NetworkPolicy** are the top two culprits. Once those are resolved, the client pod should be able to reach the server Service without time‑outs.
