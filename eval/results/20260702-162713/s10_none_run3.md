# s10 | mode=none | run=3 | 11.4s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons a pod cannot reach a service in the same namespace  

| # | Possible cause | Why it matters | How to verify | How to fix |
|---|----------------|----------------|---------------|-----------|
| 1 | **Service has no endpoints** (the selector does not match the server pod) | If the Service’s endpoint list is empty, traffic is dropped at the Service IP level. | ```bash\nkubectl get svc s10-server -n kubexplain-eval -o wide\nkubectl get endpoints s10-server -n kubexplain-eval\n```<br>Check that the `ENDPOINTS` column shows the IP(s) of the server pod. | Adjust the Service selector so it matches the server pod labels, or add the correct labels to the pod. Example: ```bash\nkubectl edit svc s10-server -n kubexplain-eval\n``` and change `spec.selector`. Then confirm endpoints appear. |
| 2 | **NetworkPolicy blocks traffic** | A NetworkPolicy that denies ingress to the server pod (or egress from the client) will cause the connection to time‑out even though the pods are healthy. | ```bash\nkubectl get networkpolicy -n kubexplain-eval\nkubectl describe networkpolicy <policy-name> -n kubexplain-eval\n```<br>Look for policies that select the server pod (`podSelector`) and have no `ingress` rule allowing traffic from the client (or from the namespace). | Either add an appropriate `ingress` rule to the policy, or delete/modify the policy. Example: ```bash\nkubectl edit networkpolicy <policy-name> -n kubexplain-eval\n``` and add:\n```yaml\ningress:\n- from:\n  - podSelector: {}\n``` (allow from any pod) or restrict to the client label. |
| 3 | **Cluster DNS resolution failure** (client cannot resolve `s10-server` to the Service IP) | If the DNS query fails, the client will try to connect to a non‑existent IP, resulting in a timeout. | ```bash\nkubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server\nkubectl exec -it s10-client -n kubexplain-eval -- cat /etc/resolv.conf\n```<br>Confirm the Service name resolves to a ClusterIP. | Restart the `coredns` (or equivalent DNS) deployment, or fix any mis‑configuration in the `coredns` ConfigMap. Example: ```bash\nkubectl rollout restart deployment/coredns -n kube-system\n``` |
| 4 | **Service type / port mismatch** (wrong `port`/`targetPort` or using `NodePort`/`LoadBalancer` incorrectly) | If the Service’s `port` does not match the container’s listening port, traffic is sent to the wrong port and appears to time out. | ```bash\nkubectl describe svc s10-server -n kubexplain-eval\nkubectl describe pod s10-server-<id> -n kubexplain-eval\n```<br>Check that `spec.ports[].port` matches the port the server container is listening on (`targetPort`). | Edit the Service to use the correct `port`/`targetPort`. Example: ```bash\nkubectl edit svc s10-server -n kubexplain-eval\n``` and adjust the fields. |
| 5 | **Pod IP conflict or IP tables issue** (CNI plugin problem) | A malfunctioning CNI can assign duplicate IPs or drop packets, causing intra‑namespace traffic to fail while the pod still shows `Ready`. | ```bash\nkubectl get pod s10-server -n kubexplain-eval -o jsonpath='{.status.podIP}'\nkubectl get pod s10-client -n kubexplain-eval -o jsonpath='{.status.podIP}'\n```<br>Ping from client to server IP: ```bash\nkubectl exec -it s10-client -n kubexplain-eval -- ping -c 3 <server-pod-ip>\n``` | If ping fails, restart the CNI daemonset (e.g., Calico, Flannel) or node. Example: ```bash\nkubectl rollout restart daemonset <cni-daemonset> -n kube-system\n``` |
| 6 | **Pod security policies / SELinux / AppArmor** blocking outbound connections | Some PSP or security context may prevent the client from opening sockets to the Service IP. | ```bash\nkubectl describe pod s10-client -n kubexplain-eval | grep -i securityContext -A5\n```<br>Look for `runAsUser`, `allowPrivilegeEscalation`, `seccompProfile`, etc. | Adjust the pod’s `securityContext` or the PSP/PodSecurityAdmission to permit network traffic. |
| 7 | **Service is mis‑named in the client code** (e.g., using wrong namespace or DNS suffix) | If the client uses `s10-server.kubexplain-eval.svc.cluster.local` incorrectly, DNS may resolve but the request goes to the wrong Service. | Verify the exact hostname used in the client logs or code. | Update the client configuration to use the correct fully‑qualified Service name or rely on the short name (`s10-server`). |

---

### General verification workflow (when you regain cluster access)

1. **Confirm Service → Endpoints**  
   ```bash
   kubectl get svc s10-server -n kubexplain-eval -o wide
   kubectl get endpoints s10-server -n kubexplain-eval
   ```

2. **Check pod labels vs Service selector**  
   ```bash
   kubectl get pod -l <selector-labels> -n kubexplain-eval
   ```

3. **Test DNS from the client**  
   ```bash
   kubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server
   ```

4. **Test connectivity to the Service IP and to the pod IP**  
   ```bash
   # Service IP
   kubectl exec -it s10-client -n kubexplain-eval -- curl -v http://<service-clusterIP>:<port>
   # Direct pod IP
   kubectl exec -it s10-client -n kubexplain-eval -- curl -v http://<server-pod-ip>:<port>
   ```

5. **Inspect NetworkPolicies**  
   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

6. **Review CNI / DNS health**  
   ```bash
   kubectl get pods -n kube-system -l k8s-app=coredns
   kubectl logs -n kube-system -l k8s-app=coredns
   ```

7. **Look at pod logs for connection errors**  
   ```bash
   kubectl logs s10-client -n kubexplain-eval
   ```

Follow the “How to fix” column for the first cause that matches what you discover. Usually the **missing endpoints** or a **restrictive NetworkPolicy** are the top culprits. If those are correct, move down the list.
