# s10 | mode=none | run=2 | 6.3s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

* The **NetworkPolicy `s10-deny-all-ingress`** selects the server pod (`app=s10-server`) and declares only an **Ingress** policy type.  
* Because the policy does **not** contain any `ingress` rules, the default behaviour is **“deny all inbound traffic”** to the selected pods.  
* Consequently, any pod that tries to reach `s10-server` – including your `s10‑client` pod – is blocked at the CNI layer, so the `wget` attempts time out even though the server pod is running and its container is healthy.

### How to fix it

You have two main options:

1. **Remove the deny‑all policy** (if you don’t need any restriction for this server).  
2. **Add an explicit allow rule** that permits traffic from the client (or from the whole namespace) on the required port.

Below are the two typical approaches.

---

#### Option 1 – Delete the policy (quickest)

```bash
kubectl delete networkpolicy s10-deny-all-ingress -n kubexplain-eval
```

After the policy is gone, the server pod will accept traffic from any pod in the cluster (subject to any other policies that might apply).

---

#### Option 2 – Replace it with a policy that allows the client

Create a policy that **allows ingress from pods labelled `app=s10-client` on port 80**, while still denying everything else.

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
          app: s10-client      # only allow traffic from the client pod
    ports:
    - protocol: TCP
      port: 80                # nginx port
```

Apply it:

```bash
kubectl apply -f s10-allow-client-ingress.yaml
```

*If you still want a “default deny” posture for the server, keep the original deny‑all policy **and** add this new policy. In Kubernetes, multiple NetworkPolicies are AND‑ed together, so the explicit allow rule will override the default deny for the specified source.*

---

### Verify the fix

1. **Check the policy is in place**

   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

2. **Test connectivity again from the client pod**

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- wget -qO- http://s10-server
   ```

   You should now receive the Nginx welcome page (or at least a successful HTTP response) instead of a timeout.

---

### TL;DR

*Root cause*: `s10-deny-all-ingress` denies all inbound traffic to `s10-server`.  
*Fix*: Delete that policy or add an ingress rule that explicitly permits traffic from `s10-client` (or from the whole namespace) on port 80. Once the policy allows the traffic, the client’s `wget` will succeed.
