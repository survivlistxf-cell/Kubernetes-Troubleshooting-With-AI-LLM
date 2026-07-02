# s10 | mode=none | run=3 | 8.0s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

## Root cause  

The namespace has a **NetworkPolicy** named `s10-deny-all-ingress` that selects the server pod (`app=s10-server`) and only specifies `policyTypes: [Ingress]` with **no** `ingress` rules.  

In Kubernetes a NetworkPolicy that selects a pod and does **not** include any `ingress` rules turns the pod into a *default‑deny* for inbound traffic.  
Therefore every connection attempt from `s10‑client` (or any other pod) to `s10‑server` is dropped by the CNI (Calico), which is why the `wget` commands time out even though the server pod is healthy.

## How to fix it  

You have two main options:

### 1. Remove the deny‑all policy (if you want the server to be reachable from everywhere)

```bash
kubectl delete netpol s10-deny-all-ingress -n kubexplain-eval
```

### 2. Keep the policy but add an explicit allow rule for the client (or for the whole namespace)

#### a) Patch the existing policy to allow traffic from the client pod

```bash
kubectl patch netpol s10-deny-all-ingress -n kubexplain-eval \
  --type='json' -p='[
    {"op":"add","path":"/spec/ingress","value":[
      {"from":[{"podSelector":{"matchLabels":{"app":"s10-client"}}}],
       "ports":[{"protocol":"TCP","port":80}]}
    ]}
  ]'
```

#### b) Or create a separate allow‑policy (leaves the deny‑all policy untouched)

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client
    ports:
    - protocol: TCP
      port: 80
EOF
```

> **Tip:** If you want to allow any pod in the same namespace, replace the `podSelector` under `from` with an empty selector (`{}`) or use `namespaceSelector` that matches the current namespace.

## Verify the fix  

1. **Check the effective policies**

   ```bash
   kubectl get netpol -n kubexplain-eval
   ```

2. **From the client pod, retry the request**

   ```bash
   kubectl exec -n kubexplain-eval s10-client -- wget -qO- http://s10-server
   ```

   You should now receive the Nginx default page (or whatever the server serves) instead of a timeout.

3. **Optional – view the flow logs (Calico)** to confirm that packets are no longer dropped.

---

**Summary:** The `s10-deny-all-ingress` NetworkPolicy is blocking all inbound traffic to `s10-server`. Remove it or add an explicit ingress rule that permits traffic from `s10-client` (or from the whole namespace) to restore connectivity.
