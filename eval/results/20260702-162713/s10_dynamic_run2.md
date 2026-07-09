# s10 | mode=dynamic | run=2 | 15.1s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **s10‑client** can’t reach **s10‑server** (same namespace)

Below the causes are ordered from “most common” to “least common”.  
For each one you’ll see:

* **What to check** – the key symptom or output to look for.  
* **How to verify** – the `kubectl` commands (or in‑pod checks) that reveal the problem.  
* **How to fix** – the change you need to make.

---

### 1. Service does not exist or is mis‑named  
**Why it matters** – If the Service object isn’t present, DNS won’t resolve the name and the client will time‑out.

**Verify**  

```bash
kubectl get svc s10-server -n kubexplain-eval
```

*If you get “Error from server (NotFound) …” the Service is missing or the name is wrong.*

**Fix**  

Create or rename the Service so the name matches what the client uses:

```bash
kubectl expose deployment s10-server --name=s10-server --port=80 --target-port=8080 -n kubexplain-eval
```

(or apply the correct YAML).

---

### 2. Service selector does not match the server pod’s labels  
**Why it matters** – The Service will have no EndpointSlice, so traffic has nowhere to go.

**Verify**  

```bash
kubectl describe svc s10-server -n kubexplain-eval | grep Selector
kubectl get pods -l <selector‑from‑svc> -n kubexplain-eval
```

*If the second command returns no pods, the selector is wrong.*

**Fix**  

Either adjust the Service selector to the labels that the server pod actually has, **or** add the expected labels to the pod/deployment.

```bash
kubectl label pod <server-pod> app=s10-server -n kubexplain-eval
# or edit the Service YAML to use the correct selector
```

---

### 3. TargetPort mismatch (Service points to a port the pod isn’t listening on)  
**Why it matters** – The Service forwards traffic to a port that the server container never opens, causing a timeout.

**Verify**  

```bash
kubectl get svc s10-server -n kubexplain-eval -o jsonpath='{.spec.ports[*].targetPort}'
kubectl describe pod <server-pod> -n kubexplain-eval | grep -i port
```

*Compare the `targetPort` with the container’s actual listening port.*

**Fix**  

Edit the Service (or the pod) so the `targetPort` matches the container’s exposed port.

```bash
kubectl edit svc s10-server -n kubexplain-eval   # change targetPort
# or change the container’s port definition in the Deployment
```

---

### 4. Server pod is healthy but the container is not listening on the expected port  
**Why it matters** – The pod may be Running, but the application inside may have crashed or is bound to `127.0.0.1` only.

**Verify** – From inside the client pod (or any pod in the same namespace):

```bash
kubectl exec -it s10-client -n kubexplain-eval -- sh
# inside the shell
nc -zv s10-server 80   # use the Service port
# or
wget -qO- http://s10-server:80/   # if HTTP
```

If the connection fails, exec into the server pod and check the listening sockets:

```bash
kubectl exec -it <server-pod> -n kubexplain-eval -- netstat -tlnp
```

*Look for the expected port and that it’s bound to `0.0.0.0` (or `::`).*

**Fix**  

Modify the application configuration to listen on all interfaces (`0.0.0.0`) and/or on the correct port. Redeploy the pod after the change.

---

### 5. NetworkPolicy blocks traffic between the two pods  
**Why it matters** – A `NetworkPolicy` that selects the server pod but does not allow traffic from the client will drop packets silently.

**Verify**  

```bash
kubectl get networkpolicy -n kubexplain-eval
kubectl describe networkpolicy <policy-name> -n kubexplain-eval
```

*Check the `podSelector` and `ingress` rules. If there is a policy that selects `s10-server` but does not list the client’s pod selector or namespace, traffic is blocked.*

**Fix**  

Either:

* Add an ingress rule that allows traffic from the client (or from the whole namespace), **or**
* Delete/disable the restrictive NetworkPolicy if you don’t need it.

Example rule addition:

```yaml
# add to the existing policy
ingress:
- from:
  - podSelector:
      matchLabels:
        app: s10-client
```

Apply the updated policy.

---

### 6. kube‑proxy / iptables (or IPVS) not functioning on the node(s)  
**Why it matters** – If kube‑proxy is mis‑configured or its rules are missing, the Service IP will not be translated to the pod IPs.

**Verify** – From any node (or a privileged pod) list the iptables rules for the Service IP:

```bash
iptables-save | grep 10\.0\.0\.0   # replace with the Service ClusterIP
```

Or, if you use IPVS mode:

```bash
ipvsadm -Ln | grep <ClusterIP>
```

*If you see no DNAT or IPVS entry for the Service, kube‑proxy is not handling it.*

**Fix**  

* Restart kube‑proxy on the affected node(s):

```bash
kubectl -n kube-system rollout restart daemonset/kube-proxy
```

*Check the kube‑proxy logs for errors (`kubectl logs -n kube-system ds/kube-proxy`).

If the problem persists, verify that the node’s firewall (e.g., `iptables` rules outside of kube‑proxy) isn’t dropping the traffic.

---

### 7. Hair‑pin (self‑IP) issue – client and server on the same node cannot reach the Service IP  
**Why it matters** – In some CNI setups, a pod that tries to reach its own Service IP (or another pod on the same node) may be blocked unless hairpin mode is enabled.

**Verify**  

*Check whether the client and server are scheduled on the **same node**:*

```bash
kubectl get pod s10-client -n kubexplain-eval -o wide
kubectl get pod s10-server -n kubexplain-eval -o wide
```

*If they share a node, try accessing the server **directly via its pod IP** from the client. If that works but the Service IP still fails, hairpin is the culprit.*

**Fix**  

Enable hairpin mode for the node’s kubelet (most managed clusters have it on by default). If you control the node:

```bash
# edit the kubelet config or start flag
--hairpin-mode=hairpin-veth
```

Then restart the kubelet. After that the Service IP should be reachable from pods on the same node.

---

### 8. DNS resolution failure (unlikely when both pods are in the same namespace)  
**Why it matters** – If the client cannot resolve `s10-server` to the Service ClusterIP, the request times out.

**Verify**  

```bash
kubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server
```

*You should see the Service’s ClusterIP. If you get “server can't find” or an empty answer, DNS is broken.*

**Fix**  

* Ensure the `kube-dns` (or `CoreDNS`) pods are healthy:

```bash
kubectl get pods -n kube-system -l k8s-app=kube-dns
```

* Restart the DNS deployment if needed, or check the `coredns` ConfigMap for errors.

---

## Quick “first‑check” checklist

1. **Service exists & name correct** – `kubectl get svc s10-server`.
2. **Selector matches server pod** – `kubectl describe svc` + `kubectl get pods -l …`.
3. **TargetPort matches what the server listens on** – compare Service spec vs pod container ports.
4. **Server actually listening** – `nc -zv s10-server <port>` from client; `netstat` inside server pod.
5. **NetworkPolicy allowing traffic** – `kubectl get networkpolicy`.
6. **kube‑proxy rules present** – `iptables-save` / `ipvsadm`.
7. **Hairpin mode if client/server on same node** – check node placement, test pod‑IP access.
8. **DNS resolves** – `nslookup s10-server` from client.

Run through the list in order; the first failure you encounter is almost always the root cause. Fix it, then re‑test the client request. Once the request succeeds, the problem is resolved.
