# s10 | mode=static | run=1 | 8.2s | needs_search=0

**Q:** Pod 's10-client' in namespace 'kubexplain-eval' cannot reach service 's10-server' in the same namespace (requests time out), although the server pod is healthy. The network policies of the namespace are attached. What is the root cause and how do I fix it?

**Expected:** deny-all ingress NetworkPolicy blocks the traffic; allow the client or remove policy

---

### What’s happening

* The **NetworkPolicy** `s10‑deny‑all‑ingress` selects the server pod (`app=s10-server`) and declares only the **Ingress** policy type.  
* Because the policy does **not** contain any `ingress` rules, it **defaults to “deny all inbound traffic”** for the selected pods.  
* Consequently every connection that the client pod (`s10‑client`) tries to make to the server pod – whether it goes through the Service’s ClusterIP or directly to the pod IP – is dropped by the CNI (Calico in this case).  
* The server pod itself is healthy, but the network policy blocks the traffic, which is why the client’s `wget` repeatedly times‑out.

### How to fix it

You need to add an **allow rule** (or remove the deny‑all policy) so that the server pod can receive traffic from the client pod (or from the whole namespace, depending on the desired security model).

#### Option 1 – Permit only the client pod

```bash
kubectl -n kubexplain-eval patch networkpolicy s10-deny-all-ingress \
  --type='json' -p='[
    {"op":"add","path":"/spec/ingress","value":[
      {"from":[{"podSelector":{"matchLabels":{"app":"s10-client"}}}],"ports":[{"protocol":"TCP","port":80}]}
    ]}
  ]'
```

*Adds an ingress rule that allows TCP 80 traffic from pods labelled `app=s10-client`.*

#### Option 2 – Permit any pod in the same namespace

```bash
kubectl -n kubexplain-eval patch networkpolicy s10-deny-all-ingress \
  --type='json' -p='[
    {"op":"add","path":"/spec/ingress","value":[
      {"from":[{"namespaceSelector":{}}],"ports":[{"protocol":"TCP","port":80}]}
    ]}
  ]'
```

*Allows traffic from any pod in `kubexplain-eval` (useful for simple demo scenarios).*

#### Option 3 – Remove the deny‑all policy altogether (if you don’t need any restriction)

```bash
kubectl -n kubexplain-eval delete networkpolicy s10-deny-all-ingress
```

### Verify the fix

1. **Check the policy** – make sure the `ingress` section now appears:

   ```bash
   kubectl -n kubexplain-eval get networkpolicy s10-deny-all-ingress -o yaml
   ```

2. **Test connectivity** from the client pod:

   ```bash
   kubectl -n kubexplain-eval exec pod/s10-client -- wget -qO- http://s10-server
   ```

   You should receive the Nginx default page (or whatever the server serves) instead of a timeout.

### TL;DR

The root cause is the **NetworkPolicy that denies all inbound traffic to the server pod** because it has no ingress rules. Add an appropriate ingress rule (or delete the policy) to allow traffic from the client pod (or the whole namespace), then the client will be able to reach the service.
