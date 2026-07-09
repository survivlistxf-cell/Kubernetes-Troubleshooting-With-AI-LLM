# s10 | mode=dynamic | run=3 | 9.1s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

* The **NetworkPolicy `s10-deny-all-ingress`** selects every pod that has the label `app=s10‑server`.
* Its spec only contains a **podSelector** and `policyTypes: [Ingress]` – **no `ingress` rules are defined**.
* In Kubernetes a NetworkPolicy that selects a pod and **does not list any allowed ingress rules** is interpreted as **“deny all inbound traffic”** for those pods.

Therefore the `s10-server` pod is **blocked from receiving any traffic**, including the HTTP request that the `s10-client` pod is trying to make. The client’s `wget` repeatedly times‑out because the packets are dropped by the CNI (Calico) before they reach the server.

### How to fix it

You have two typical options:

1. **Remove the deny‑all policy** (if you don’t need any restriction on `s10-server`), or  
2. **Add an explicit allow rule** that permits traffic from the client (or from the whole namespace).

Below are concrete commands for each approach.

---

#### Option 1 – Delete the policy (quickest)

```bash
kubectl delete networkpolicy s10-deny-all-ingress -n kubexplain-eval
```

After the policy is gone, the server pod will accept traffic from any source (the default “allow all” behavior).

---

#### Option 2 – Keep a default‑deny but add an allow rule

Create a new policy (or patch the existing one) that allows ingress from the client pod(s).

**Create a separate “allow” policy (recommended, keeps the default‑deny intent clear):**

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: s10-allow-client-ingress
  namespace: kubexplain-eval
spec:
  podSelector:
    matchLabels:
      app: s10-server          # target the server pods
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: s10-client      # only allow pods labelled as the client
    ports:
    - protocol: TCP
      port: 80                # nginx port; adjust if you use a different one
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
    - podSelector:
        matchLabels:
          app: s10-client
    ports:
    - protocol: TCP
      port: 80
EOF
```

The original `s10-deny-all-ingress` policy will continue to act as a **default‑deny** for any other sources, while this new policy explicitly permits the client.

**Or patch the existing policy to include the allow rule:**

```bash
kubectl patch networkpolicy s10-deny-all-ingress -n kubexplain-eval \
  --type='json' -p='[{"op":"add","path":"/spec/ingress","value":[{"from":[{"podSelector":{"matchLabels":{"app":"s10-client"}}}],"ports":[{"protocol":"TCP","port":80}]}]}]'
```

---

### Verify the fix

1. **Check the policies are in place**

   ```bash
   kubectl get networkpolicy -n kubexplain-eval -o wide
   ```

2. **Test connectivity again**

   ```bash
   kubectl exec -n kubexplain-eval -it s10-client -- wget -qO- http://s10-server
   ```

   You should now see the Nginx welcome page (or whatever content the server serves) instead of a timeout.

---

### Summary

*Root cause*: A NetworkPolicy that selects `s10-server` but defines **no ingress rules** → all inbound traffic is blocked.  
*Fix*: Either delete that deny‑all policy or add an explicit ingress rule (e.g., allow from pods labelled `app=s10-client`). After applying the change, the client can reach the server.
