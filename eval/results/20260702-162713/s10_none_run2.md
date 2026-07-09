# s10 | mode=none | run=2 | 20.9s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Most likely reasons why **`s10-client`** cannot reach **`s10-server`** (same namespace)

Below the causes are ordered from *most common* to *least common* for a typical Kubernetes cluster.  
For each one I show:

* **What to look for** (how to verify the problem)  
* **How to fix it** (what to change)

You can run the suggested `kubectl` commands when you regain access to the cluster.

---

### 1. Service has no matching endpoints (selector mismatch)

**Why it happens**  
* The Service’s `selector` does not match the labels on the `s10‑server` pod, so the Service ends up with an empty Endpoints list.  
* The client can resolve the Service name, but the IP it receives has no backing pods → traffic times‑out.

**How to verify**  

```bash
kubectl -n kubexplain-eval get svc s10-server -o yaml
kubectl -n kubexplain-eval get endpoints s10-server -o yaml
```

*If the `ENDPOINTS` output shows `addresses: []`* → selector mismatch.

**How to fix**  

* Make the Service selector match the pod’s labels, e.g.:

```yaml
# Service
spec:
  selector:
    app: s10-server   # <-- must be identical to pod label
```

*Or* add the missing label to the pod (or its Deployment/ReplicaSet) and redeploy.

```bash
kubectl -n kubexplain-eval label pod <s10-server-pod> app=s10-server --overwrite
```

---

### 2. NetworkPolicy blocks traffic between the two pods

**Why it happens**  
* A `NetworkPolicy` in `kubexplain-eval` restricts ingress/egress and does not allow traffic from `s10-client` to `s10-server`.  
* By default, if any policy selects a pod, **only** the allowed traffic is permitted.

**How to verify**  

```bash
kubectl -n kubexplain-eval get networkpolicy -o yaml
kubectl -n kubexplain-eval describe networkpolicy <policy-name>
```

*Look for a policy that selects `s10-server` (or the whole namespace) and does **not** include an ingress rule that allows traffic from the client’s pod selector or from the whole namespace.*

**How to fix**  

* Add an ingress rule that permits the client, e.g.:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-s10-client
spec:
  podSelector:
    matchLabels:
      app: s10-server
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client
    ports:
    - protocol: TCP
      port: <service-port>
```

*Or, if you want unrestricted intra‑namespace traffic, add a policy that allows all pods in the namespace.*

```yaml
spec:
  podSelector: {}
  ingress:
  - {}
```

---

### 3. DNS resolution failure (client cannot resolve `s10-server`)

**Why it happens**  
* CoreDNS is mis‑configured, down, or the Service name is typed incorrectly.  
* The client gets an NXDOMAIN or a stale IP that does not belong to the Service.

**How to verify**  

```bash
kubectl -n kubexplain-eval exec pod/s10-client -- nslookup s10-server
kubectl -n kubexplain-eval exec pod/s10-client -- cat /etc/resolv.conf
kubectl -n kubexplain-eval get pods -l k8s-app=kube-dns -n kube-system
```

*If `nslookup` fails or returns an unexpected IP, DNS is the culprit.*

**How to fix**  

* Restart CoreDNS (or the DNS add‑on) if pods are unhealthy:

```bash
kubectl -n kube-system rollout restart deployment/coredns
```

*Check that the Service name is correct (`s10-server.kubexplain-eval.svc.cluster.local` works automatically).  
*If you have custom `dnsPolicy` or `dnsConfig` on the client pod, revert to the default (`ClusterFirst`).  

---

### 4. Port mismatch between Service and server pod

**Why it happens**  
* The Service’s `port`/`targetPort` does not correspond to the container port the server is listening on.  
* The client connects to the Service IP on the wrong port → connection reset / timeout.

**How to verify**  

```bash
kubectl -n kubexplain-eval get svc s10-server -o yaml   # look at .spec.ports
kubectl -n kubexplain-eval describe pod s10-server    # look at container ports
```

*Confirm that `targetPort` (or the default same‑as‑port) matches the container’s listening port.*

**How to fix**  

* Align the Service definition with the container port, e.g.:

```yaml
spec:
  ports:
  - port: 8080          # Service port
    targetPort: 8080    # Must be the container's port
```

*Or change the container’s `containerPort` / application config to match the Service.*

---

### 5. Pod is not Ready (readiness probe failing) → not added to Endpoints

**Why it happens**  
* Even though the pod appears “Running”, a failing readiness probe keeps it out of the Service’s endpoint list.  
* The client can resolve the Service but receives only the IPs of Ready pods (none in this case).

**How to verify**  

```bash
kubectl -n kubexplain-eval get pod s10-server -o wide
kubectl -n kubexplain-eval describe pod s10-server | grep -i readiness
kubectl -n kubexplain-eval get endpoints s10-server -o yaml
```

*If the pod status shows `Ready` = `false` and the Endpoints list is empty, the readiness probe is the issue.*

**How to fix**  

* Adjust the readiness probe (timeout, initialDelay, path, command) so it succeeds, then redeploy.  
*Or temporarily remove the readiness probe to confirm the cause.

```yaml
readinessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
```

---

### 6. kube‑proxy / iptables not programmed correctly

**Why it happens**  
* kube‑proxy (or the underlying CNI) failed to install the NAT rules for the Service, often after a node restart or upgrade.  
* The Service IP exists but traffic never reaches the pod IPs.

**How to verify**  

```bash
kubectl -n kubexplain-eval get nodes -o wide
kubectl -n kube-system get pods -l k8s-app=kube-proxy -o wide
# On a node (if you can SSH):
sudo iptables -t nat -L KUBE-SERVICES
```

*If the Service’s ClusterIP is missing from the `KUBE-SERVICES` chain, kube‑proxy is at fault.*

**How to fix**  

* Restart kube‑proxy on the affected node(s) or rollout a restart of the daemonset:

```bash
kubectl -n kube-system rollout restart daemonset/kube-proxy
```

*Check the CNI plugin logs for errors; a full node reboot often clears transient iptables mismatches.*

---

### 7. CNI plugin/network overlay problem

**Why it happens**  
* The underlying network (Calico, Flannel, Cilium, etc.) is broken – e.g., pod‑to‑pod traffic is dropped, or the overlay VXLAN/IPIP tunnels are down.  
* This can affect all intra‑namespace traffic, not just this Service.

**How to verify**  

```bash
kubectl -n kube-system get pods -l k8s-app=<cni-plugin>
kubectl -n kube-system logs <cni-pod>   # look for errors
# From a pod, try pinging another pod IP directly:
kubectl -n kubexplain-eval exec pod/s10-client -- ping -c 3 <s10-server-pod-ip>
```

*If direct pod‑IP ping fails while the pods are on the same node, the CNI is likely broken.*

**How to fix**  

* Restart the CNI daemonset:

```bash
kubectl -n kube-system rollout restart daemonset/<cni-daemonset>
```

*If the problem persists, check the CNI’s configuration (e.g., IP pool exhaustion, MTU mismatch) and the node’s network interfaces.

---

### 8. HostNetwork / hostPort conflict

**Why it happens**  
* `s10-server` is running with `hostNetwork: true` (or a hostPort) that collides with another process, making the Service’s ClusterIP route to a non‑listening port on the node.  

**How to verify**  

```bash
kubectl -n kubexplain-eval get pod s10-server -o yaml | grep hostNetwork
kubectl -n kubexplain-eval get pod s10-server -o yaml | grep hostPort
```

*If either flag is set, verify that the host port is free (`netstat -tulpn` on the node).*

**How to fix**  

* Remove `hostNetwork` / `hostPort` unless you really need them, or change the port to an unused one.

---

### 9. Service type mismatch (e.g., headless Service without proper DNS)

**Why it happens**  
* A headless Service (`clusterIP: None`) is being used but the client expects a single ClusterIP.  
* DNS returns multiple A records (pod IPs) and the client may not handle them correctly, leading to time‑outs.

**How to verify**  

```bash
kubectl -n kubexplain-eval get svc s10-server -o yaml | grep clusterIP
```

*If `clusterIP: None`, it’s a headless Service.*

**How to fix**  

* Change the Service to a normal ClusterIP Service (remove `clusterIP: None`).  
*Or, update the client to handle multiple A records (e.g., use a library that retries each IP).

---

### 10. Firewall / security‑group rules at the node level

**Why it happens**  
* The underlying cloud provider or on‑prem firewall blocks traffic on the Service port between nodes.  
* This is rare for intra‑node traffic but can happen in multi‑node clusters with strict host‑level firewalls.

**How to verify**  

*Check node‑level firewall rules (e.g., `iptables -L INPUT` or cloud security groups).  
*From one node, try `nc -zv <other-node-ip> <service-port>`.

**How to fix**  

*Open the required ports (usually the whole pod CIDR range) in the host firewall or security group.  

---

## Quick “first‑check” checklist (run when you’re back in the cluster)

1. **Endpoints exist?**  
   ```bash
   kubectl -n kubexplain-eval get endpoints s10-server
   ```
2. **NetworkPolicy allowing traffic?**  
   ```bash
   kubectl -n kubexplain-eval get networkpolicy
   ```
3. **DNS resolves correctly?**  
   ```bash
   kubectl -n kubexplain-eval exec pod/s10-client -- nslookup s10-server
   ```
4. **Ports line up?**  
   ```bash
   kubectl -n kubexplain-eval describe svc s10-server
   kubectl -n kubexplain-eval describe pod s10-server
   ```
5. **Pod Ready?**  
   ```bash
   kubectl -n kubexplain-eval get pod s10-server
   ```
6. **kube‑proxy / CNI health** – restart if anything looks off.

Address the first failing check; in most clusters the root cause is **#1 (no endpoints)** or **#2 (NetworkPolicy)**. Fixing those usually restores connectivity instantly.
