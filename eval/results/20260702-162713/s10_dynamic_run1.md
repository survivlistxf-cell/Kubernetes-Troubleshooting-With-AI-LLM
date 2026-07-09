# s10 | mode=dynamic | run=1 | 19.8s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **s10‑client** can’t reach **s10‑server** (same namespace)

Below the causes are ordered from *most common* to *least common* based on typical Kubernetes deployments.  
For each one you’ll see:

* **What to look for** (how to verify the problem)  
* **How to fix it** (what to change once you have access to the cluster)

---

### 1. Service selector does not match the server pod’s labels  
**Why it happens** – The Service’s `spec.selector` must exactly match the labels on the server pod. A typo, missing label, or using a different key (e.g. `app: s10-server` vs `run: s10-server`) leaves the Service with **no endpoints**, so traffic times‑out.

**How to verify**  

```bash
kubectl get svc s10-server -n kubexplain-eval -o yaml | grep selector -A2
kubectl get pod -l <selector‑key>=<selector‑value> -n kubexplain-eval
kubectl get endpointslice -l kubernetes.io/service-name=s10-server -n kubexplain-eval
```

* If the `EndpointsSlice` shows `ENDPOINTS: <none>` or the `kubectl get pod -l …` command returns **0 pods**, the selector is wrong.

**How to fix**  

* Edit the Service (or the pod/deployment) so the selector matches the pod labels:

```bash
kubectl edit svc s10-server -n kubexplain-eval
# adjust spec.selector to the correct key/value
```

* Or add the missing label to the server pod/deployment and roll it out.

---

### 2. Service exists but DNS name does not resolve inside the client pod  
**Why it happens** – The client tries to reach `s10-server` (or `s10-server.kubexplain-eval.svc.cluster.local`) but the cluster DNS cannot resolve it. This can be caused by:

* Service not created (or deleted) after the client started.  
* DNS add‑on (`coredns`/`kube-dns`) not running or mis‑configured.  

**How to verify**  

```bash
# From inside the client pod (once you can exec)
kubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server
kubectl exec -it s10-client -n kubexplain-eval -- dig s10-server.default.svc.cluster.local
```

* If you get “**server can't find …: NXDOMAIN**” or “**connection timed out**”, DNS is the culprit.

**How to fix**  

* Ensure the Service exists: `kubectl get svc s10-server -n kubexplain-eval`.  
* Check the DNS pods: `kubectl get pods -n kube-system -l k8s-app=kube-dns`.  
* Look at DNS logs for errors and restart the DNS deployment if needed.  
* If the Service was created after the client pod started, delete and recreate the client pod so it picks up the new DNS entry.

---

### 3. NetworkPolicy blocks traffic between the two pods  
**Why it happens** – A `NetworkPolicy` in the namespace may allow only selected pods or namespaces to talk to each other. If there is a policy that does **not** allow traffic from `s10-client` to the server pod’s label, the connection will be dropped silently (timeout).

**How to verify**  

```bash
kubectl get networkpolicy -n kubexplain-eval
kubectl describe networkpolicy <policy-name> -n kubexplain-eval
```

* Look for a policy that selects the server pod (`podSelector`) but does **not** include the client pod’s label in its `ingress.from` list.

**How to fix**  

* Add the client pod’s label (or its namespace) to the `ingress.from` section of the relevant policy, or create a new policy that explicitly permits the traffic:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-client-to-server
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server          # label on server pod
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client      # label on client pod
    ports:
    - protocol: TCP
      port: <service-port>
```

* Apply the updated policy and re‑test.

---

### 4. kube‑proxy / hair‑pin mode prevents the client from reaching the Service IP  
**Why it happens** – When a pod tries to reach its own Service IP (or a Service that resolves to a pod on the same node), the traffic must be “hair‑pinned” back to the pod. In clusters that use `iptables` mode with a bridge CNI, hair‑pinning may be disabled, causing the request to time out.

**How to verify**  

```bash
# From inside the client pod
curl -v http://s10-server.kubexplain-eval.svc.cluster.local:<port>
# If it fails, check the node’s iptables rules or kube-proxy mode:
kubectl get configmap kube-proxy -n kube-system -o yaml | grep mode
```

* If kube‑proxy is in `iptables` mode and the CNI is a simple bridge, hair‑pin may be off.

**How to fix**  

* Enable hair‑pinning on the kubelet (`--hairpin-mode=hairpin-veth` or `promiscuous-bridge`). This usually requires a node‑level change and a kubelet restart.  
* Alternatively, switch kube‑proxy to `IPVS` mode (more robust for hair‑pin traffic).  
* As a quick workaround, have the client use the **Pod IP** of the server instead of the Service IP (only for testing).

---

### 5. Server pod is not actually listening on the targetPort  
**Why it happens** – The Service may point to a `targetPort` that the server container does not expose (wrong port number, wrong protocol, or the container process is not bound to that port).

**How to verify**  

```bash
kubectl describe pod s10-server -n kubexplain-eval
# Look at the container ports section
kubectl exec -it s10-server -n kubexplain-eval -- netstat -tlnp
# or
kubectl exec -it s10-server -n kubexplain-eval -- curl localhost:<targetPort>
```

* If the port is not listening, the Service will forward traffic to nowhere, resulting in a timeout.

**How to fix**  

* Update the container (or its command) to listen on the expected port, or change the Service’s `targetPort` to match the actual port.  
* Redeploy the server pod after the change.

---

### 6. CNI plugin or node‑level network issue  
**Why it happens** – A mis‑configured CNI (Calico, Flannel, Cilium, etc.) can break pod‑to‑pod networking across nodes, even though the pods appear “Running”. This often shows up as time‑outs only for cross‑node traffic.

**How to verify**  

```bash
# From the client pod, try reaching the server pod IP directly
kubectl exec -it s10-client -n kubexplain-eval -- ping -c 3 <server-pod-IP>
kubectl exec -it s10-client -n kubexplain-eval -- curl http://<server-pod-IP>:<targetPort>
```

* If direct Pod‑IP access works on the same node but fails when the server is on a different node, the CNI is likely at fault.

**How to fix**  

* Check the CNI daemonset logs (`kubectl logs -n kube-system <cni-pod>`).  
* Restart the CNI pods or re‑apply the CNI manifest.  
* Verify that the underlying host network (e.g., MTU, firewall rules) allows the overlay traffic.

---

### 7. Pod security policies / PSP / OPA Gatekeeper constraints  
**Why it happens** – A policy may forbid the client pod from making outbound connections to certain ports or IP ranges, causing silent drops.

**How to verify**  

```bash
kubectl get psp,gatekeeperconstraint -A | grep -i deny
kubectl describe pod s10-client -n kubexplain-eval | grep -i "Allowed"
```

* Look for any rule that blocks traffic to the Service IP or port.

**How to fix**  

* Adjust the policy to allow the required egress, or add an exemption for the `s10-client` workload.

---

## Quick “when you get back into the cluster” checklist

1. **Confirm the Service exists and its selector matches the server pod**  
   ```bash
   kubectl get svc s10-server -n kubexplain-eval -o yaml
   kubectl get endpointslice -l kubernetes.io/service-name=s10-server -n kubexplain-eval
   ```

2. **Test DNS resolution from the client**  
   ```bash
   kubectl exec -it s10-client -n kubexplain-eval -- nslookup s10-server
   ```

3. **Verify there are no NetworkPolicies blocking the flow**  
   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

4. **Check that the server is listening on the expected port**  
   ```bash
   kubectl exec -it s10-server -n kubexplain-eval -- curl localhost:<targetPort>
   ```

5. **If the client and server are on the same node, ensure hair‑pin mode is enabled**  
   ```bash
   kubectl get configmap kube-proxy -n kube-system -o yaml | grep mode
   ```

6. **If cross‑node, validate CNI health**  
   ```bash
   kubectl logs -n kube-system -l k8s-app=<cni-plugin>
   ```

7. **Apply the necessary fix (adjust selector, Service spec, NetworkPolicy, kubelet flag, or CNI config)** and then retest the connection.

---

### Bottom line
The **most common** root cause is a **selector mismatch** that leaves the Service without endpoints. DNS, NetworkPolicy, and hair‑pin/kube‑proxy issues follow closely behind. Verify each layer in the order above; once the first failing layer is corrected, the client‑to‑service communication should succeed.
