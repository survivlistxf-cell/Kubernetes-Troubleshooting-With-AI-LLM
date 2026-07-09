# s10 | mode=static | run=3 | 8.1s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

The **NetworkPolicy** you have in the `kubexplain‑eval` namespace is:

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
  - Ingress                    # only ingress is controlled
```

Because the policy **does not contain any `ingress` rules**, it defaults to **“deny all inbound traffic”** for any pod that matches the selector (`app=s10-server`).  
Therefore the `s10-client` pod’s attempts to reach the `s10-server` service are blocked at the CNI layer, which is why `wget` repeatedly times‑out.

### How to fix it

You have two main options:

1. **Remove the deny‑all policy** (if you don’t need it)  
2. **Add an explicit allow rule** that permits traffic from the client (or from the whole namespace) to the server.

#### Option 1 – Delete the policy

```bash
kubectl delete networkpolicy s10-deny-all-ingress -n kubexplain-eval
```

After the policy is gone, the server pod will accept traffic from any source (the default “allow all” behavior).

#### Option 2 – Replace it with a policy that allows the client

Create a new policy (or edit the existing one) that adds an ingress rule.  
For example, to allow any pod in the same namespace to talk to the server on port 80:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector: {}          # any pod in this namespace
    ports:
    - protocol: TCP
      port: 80
```

Apply it:

```bash
kubectl apply -f - <<'EOF'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector: {}
    ports:
    - protocol: TCP
      port: 80
EOF
```

If you want to be more restrictive (e.g., only allow the specific client pod), replace the `namespaceSelector` with a `podSelector` that matches the client’s labels:

```yaml
  - from:
    - podSelector:
        matchLabels:
          app: s10-client
```

### Verify the fix

1. **Check the policy is in place**

   ```bash
   kubectl get networkpolicy -n kubexplain-eval
   ```

2. **Test connectivity again from the client pod**

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- wget -qO- http://s10-server
   ```

   You should now receive the Nginx default page (or whatever the server serves) instead of a timeout.

### Summary

- The root cause is the `s10-deny-all-ingress` NetworkPolicy, which blocks all inbound traffic to the `s10-server` pod.
- Fix it by deleting that policy or by adding an ingress rule that explicitly permits traffic from the client (or from the whole namespace). Once the policy allows the traffic, the client’s `wget` calls will succeed.
