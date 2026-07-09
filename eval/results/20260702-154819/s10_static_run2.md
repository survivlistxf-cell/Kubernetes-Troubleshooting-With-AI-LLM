# s10 | mode=static | run=2 | 9.3s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

* The **s10‑server** pod is running and has an IP `10.244.226.79`.  
* The **s10‑client** pod tries to reach the server (via `wget`) but every request ends with **“download timed out”** – the traffic never reaches the server container.  
* In the namespace **kubexplain‑eval** there is a single NetworkPolicy named **s10‑deny‑all‑ingress**:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-deny-all-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server          # selects the server pod
  policyTypes:
  - Ingress
```

* Because the policy **does not contain any `ingress` rules**, it implements the default‑deny behavior for **all inbound traffic** to pods that match the selector (`app=s10‑server`).  
* Consequently, any connection attempt from `s10-client` (or any other pod) to the server is dropped by the CNI (Calico), which is why `wget` always times out.

**Root cause:** a *deny‑all ingress* NetworkPolicy is applied to the server pod without an accompanying rule that permits traffic from the client (or from the service IP).

---

### How to fix it

You have two options:

1. **Add an explicit allow rule** to the existing policy (or create a new one) that permits traffic from the client pod (or from the whole namespace) to the server pod.

2. **Remove or disable the deny‑all policy** if you don’t need it.

Below are the most common ways to implement option 1.

#### A. Extend the existing policy with an allow rule

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server          # target the server pod
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client    # allow only the client pod
    ports:
    - protocol: TCP
      port: 80               # nginx port
```

Apply it:

```bash
kubectl apply -f s10-allow-client-ingress.yaml
```

*Result:* traffic from any pod labeled `app=s10-client` to port 80 on the server pod is allowed, while all other inbound traffic remains blocked.

#### B. If you want to allow **any pod in the same namespace** (simpler for a demo)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-ns-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector: {}   # any pod in this namespace
    ports:
    - protocol: TCP
      port: 80
```

Apply it the same way.

#### C. Remove the deny‑all policy (if you don’t need any restrictions)

```bash
kubectl delete networkpolicy s10-deny-all-ingress -n kubexplain-eval
```

*Result:* the server pod will accept traffic from any source (the default “allow all” behavior when no NetworkPolicy selects the pod).

---

### Quick verification steps

1. **Check the effective policy** after you apply the change:

   ```bash
   kubectl get networkpolicy -n kubexplain-eval -o yaml
   ```

2. **Test connectivity again** from the client pod:

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- wget -qO- http://s10-server
   ```

   You should now see the HTML page served by the nginx container.

3. **Confirm the service resolves** (optional):

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- nslookup s10-server
   ```

   The service name should resolve to the cluster‑IP of the service, which in turn maps to the server pod’s IP.

---

### TL;DR

*The `s10-deny-all-ingress` NetworkPolicy is blocking all inbound traffic to the `s10-server` pod.*  
Add an ingress rule that permits traffic from the client (or delete the deny‑all policy). Once the policy allows traffic on port 80, the client’s `wget` will succeed.
