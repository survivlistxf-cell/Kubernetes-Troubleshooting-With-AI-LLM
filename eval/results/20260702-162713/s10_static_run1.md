# s10 | mode=static | run=1 | 16.1s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons the client pod cannot reach the server service (ordered by probability)

### 1. Service selector does **not** match the server pod’s labels  
**Why it matters** – If the selector is wrong the Service will have no endpoints, so traffic to the ClusterIP is dropped.  
**How to verify** –  
* Look at the Service’s `spec.selector` and compare it with the `metadata.labels` on the server pod (or its Deployment/ReplicaSet).  
* Run `kubectl get endpoints s10‑server` (or `kubectl get endpointslices -l kubernetes.io/service-name=s10-server`). If the list is empty, the selector is the problem.  
**Fix** – Align the selector with the pod labels, or add the expected label to the server pod’s template. After the change the endpoints will appear automatically.

---

### 2. No EndpointSlice / Endpoints created for the Service  
**Why it matters** – Even with a correct selector, the controller may have failed to create EndpointSlices (e.g., because the server pod is not Ready).  
**How to verify** –  
* Check the `READY` condition of the server pod (`kubectl describe pod s10‑server`).  
* Inspect the EndpointSlice objects for the Service; they should list the pod IPs.  
**Fix** – Ensure the server pod reaches the `Ready` state (no crash loops, correct container ports, health checks). If the pod is Ready but endpoints are still missing, restart the EndpointSlice controller or delete the stale EndpointSlice so it can be regenerated.

---

### 3. DNS resolution of the Service name fails inside the client pod  
**Why it matters** – The client may be using the DNS name (`s10-server`) and never reaches the ClusterIP if DNS cannot resolve it.  
**How to verify** –  
* Exec into the client pod (or a temporary busybox pod) and run `nslookup s10-server` or `dig s10-server.default.svc.cluster.local`.  
* Verify that the returned IP matches the Service’s ClusterIP.  
**Fix** –  
* If DNS returns “NXDOMAIN” or a different IP, check that the `kube-dns`/`coredns` deployment is healthy.  
* Ensure the Service exists in the same namespace (or use a fully‑qualified name).  
* Restart the DNS pods if they are unhealthy.

---

### 4. NetworkPolicy blocks traffic from the client to the server  
**Why it matters** – A `NetworkPolicy` that selects the server pod may restrict inbound traffic to a specific set of pods or namespaces.  
**How to verify** –  
* List all `NetworkPolicy` objects in the namespace and look for any that select `s10-server` (by pod selector).  
* Examine the `ingress` rules; if they only allow traffic from other pods or specific ports, the client may be excluded.  
**Fix** –  
* Add the client pod’s label (or its namespace) to the allowed `ingress` sources, or create a new permissive policy for this Service.  
* If you don’t need any policy, delete the restrictive `NetworkPolicy`.

---

### 5. Service port / targetPort mismatch or wrong protocol  
**Why it matters** – The Service may expose a port that the server pod is not listening on, or the protocol (TCP/UDP) is incorrect.  
**How to verify** –  
* Inspect the Service’s `spec.ports` (port, targetPort, protocol).  
* Check the server container’s `containerPort` and the actual process listening inside the pod (`netstat -tlnp` or similar).  
**Fix** – Align the Service port with the container’s listening port, or change `targetPort` to the correct value. Ensure the protocol matches (most services use TCP).

---

### 6. Hair‑pin / “pod‑to‑service‑IP” traffic not enabled on the node  
**Why it matters** – When a pod tries to reach its own Service IP, the traffic must be “hair‑pinned” back to the node’s bridge. Some CNI plugins or kube‑proxy iptables mode require the `hairpin-mode` flag.  
**How to verify** –  
* From the client pod, try reaching the Service IP directly (`curl http://<ClusterIP>:<port>`).  
* If the pod can reach the server by **direct pod IP** but not by Service IP, hair‑pin is likely the issue.  
**Fix** –  
* Enable hair‑pin mode on the kubelet (`--hairpin-mode=hairpin-veth` or `promiscuous-bridge`).  
* Restart the kubelet or the node to apply the change.  

---

### 7. kube‑proxy malfunction on the node where the client pod runs  
**Why it matters** – kube‑proxy programs iptables/ipvs rules that forward Service IP traffic to pod endpoints. If those rules are missing or broken, the Service appears unreachable.  
**How to verify** –  
* Check the kube‑proxy logs on the node for errors.  
* On the node, list iptables rules (`iptables-save`) or ipvs entries (`ipvsadm -Ln`) for the Service’s ClusterIP and port.  
**Fix** –  
* Restart kube‑proxy with higher verbosity to surface the issue.  
* If the node’s kube‑proxy is stuck, delete the pod so it is recreated, or roll out a new kube‑proxy daemonset version.

---

### 8. Underlying CNI / overlay network problem (e.g., Flannel, Calico)  
**Why it matters** – If the pod network cannot route between nodes, the client’s traffic to the Service IP (which may be on a different node) will be dropped.  
**How to verify** –  
* Ping the server pod’s IP from the client pod.  
* Verify node‑to‑node connectivity (e.g., `ping` or `traceroute` between the two node IPs).  
* Look at CNI plugin logs for errors.  
**Fix** –  
* Restart the CNI plugin daemonset.  
* Re‑apply the CNI configuration or upgrade to a stable version.  
* Ensure the underlying network (VLAN, VxLAN, etc.) is not fragmented or blocked by firewall rules.

---

### 9. Service is of type `ClusterIP` but client pod is using an external IP or load‑balancer address  
**Why it matters** – In the same namespace the client should use the DNS name or ClusterIP; using an external load‑balancer address may route outside the cluster and be blocked.  
**How to verify** –  
* Confirm the client is calling `http://s10-server` (or the ClusterIP) rather than an external address.  
**Fix** – Update the client configuration to use the internal Service name or ClusterIP.

---

## How to proceed when you regain cluster access  

1. **Confirm the Service and endpoints** – `kubectl describe svc s10-server` and `kubectl get endpoints/endpointSlices`.  
2. **Validate DNS** – exec into a test pod and run `nslookup`/`dig`.  
3. **Check NetworkPolicy** – `kubectl get netpol -n kubexplain-eval`.  
4. **Inspect pod readiness and ports** – `kubectl describe pod s10-server`.  
5. **Review kube‑proxy and CNI logs** – `kubectl logs -n kube-system <kube-proxy-pod>` and the CNI daemonset logs.  
6. **If hair‑pin is needed** – ensure the kubelet flag is set and restart the node.  

Address the first failing check you encounter; fixing that most‑common cause (selector/endpoint mismatch) usually resolves the connectivity issue. If it persists, move down the list until the root cause is identified.
